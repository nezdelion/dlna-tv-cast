package io.github.nezdelion.dlnatvcast.core.cast

import io.github.nezdelion.dlnatvcast.core.MediaItem
import io.github.nezdelion.dlnatvcast.core.http.MediaServer
import io.github.nezdelion.dlnatvcast.core.session.CastSession
import io.github.nezdelion.dlnatvcast.core.session.QueueEntry
import io.github.nezdelion.dlnatvcast.core.session.RendererClient
import io.github.nezdelion.dlnatvcast.core.session.SessionTiming
import io.github.nezdelion.dlnatvcast.core.upnp.RendererDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

/** Why nothing is being cast; the renderer was not contacted. */
sealed interface CastFailure {
    data object NoNetwork : CastFailure
    data class NothingReadable(val shared: Int) : CastFailure
    data class ServerFailed(val message: String) : CastFailure
}

sealed interface CastPhase {
    data object Idle : CastPhase
    data object ReadingItems : CastPhase
    data class Connecting(val rendererName: String) : CastPhase

    /** A queue is ready but no renderer is available yet. */
    data class ChooseRenderer(val itemCount: Int) : CastPhase
    data class Casting(val session: CastSession, val renderer: RendererDevice) : CastPhase
    data class Failed(val failure: CastFailure) : CastPhase
}

data class CoordinatorState(
    val phase: CastPhase = CastPhase.Idle,
    /** Shared items that could not be opened (unreadable or not granted). */
    val skipped: Int = 0,
)

/** What [CastCoordinator] needs from the platform; Android implements it in the service. */
interface CastPlatform<T> {
    /** IPv4 address on Wi-Fi/Ethernet, or null when there is none. */
    fun lanAddress(): InetAddress?

    /** Opens one shared item; null when it cannot be read or is not media. */
    suspend fun inspect(item: T): MediaItem?

    fun startServer(items: List<MediaItem>, lan: InetAddress): MediaServer

    /** Name of the remembered renderer, or null when none was used yet. */
    fun rememberedName(): String?

    /** The remembered renderer if it is reachable now (bounded by the platform). */
    suspend fun resolveRemembered(lan: InetAddress): RendererDevice?

    fun remember(device: RendererDevice)

    fun client(device: RendererDevice): RendererClient

    /** Hold or release CPU/Wi-Fi wake locks; called on every transition, must be idempotent. */
    fun keepAwake(on: Boolean)

    /** Nothing left to do: the host (service) may stop. */
    fun finished()
}

/**
 * Lifecycle of shares and cast sessions, independent of Android.
 *
 * Every share, end and destroy starts a new generation and detaches the resources it replaces
 * synchronously, so cleanup that finishes later only ever touches what it owned and never stops
 * a newer share. Must be driven from one thread (the service's main thread).
 */
class CastCoordinator<T>(
    private val scope: CoroutineScope,
    private val platform: CastPlatform<T>,
    /** Outlives [scope]: used to send a best-effort Stop after the host is destroyed. */
    private val cleanupScope: CoroutineScope,
    private val timing: SessionTiming = SessionTiming(),
) {
    private val _state = MutableStateFlow(CoordinatorState())
    val state: StateFlow<CoordinatorState> = _state.asStateFlow()

    private class Resources(val server: MediaServer, val items: List<MediaItem>) {
        var session: CastSession? = null
    }

    private var generation = 0L
    private var prepareJob: Job? = null
    private var current: Resources? = null

    /** A new share replaces whatever was queued or playing. */
    fun share(items: List<T>, unreadable: Int) {
        val gen = ++generation
        prepareJob?.cancel()
        // The renderer simply gets the new item; no Stop for the replaced queue.
        detach()?.let { release(it, stopRenderer = false) }
        platform.keepAwake(false)
        _state.value = CoordinatorState(CastPhase.ReadingItems)
        prepareJob = scope.launch { prepare(gen, items, unreadable) }
    }

    fun select(device: RendererDevice) {
        val res = current ?: return
        prepareJob?.cancel()
        res.session?.let { old -> scope.launch { old.close(stopRenderer = false) } }
        res.session = null
        startSession(res, device)
    }

    /** User ends casting: Stop on the renderer, local server closed, host finishes. */
    fun end() {
        val gen = ++generation
        prepareJob?.cancel()
        val old = detach()
        platform.keepAwake(false)
        _state.value = CoordinatorState(CastPhase.Idle)
        // URLs stop working at once; the Stop to the renderer may take a while.
        old?.server?.close()
        scope.launch {
            old?.session?.close(stopRenderer = true)
            if (gen == generation) platform.finished()
        }
    }

    /**
     * Host is being destroyed: never suspends. The local server is closed synchronously, the
     * renderer gets a best-effort Stop in [cleanupScope], bounded by the session's timeout.
     */
    fun destroy() {
        ++generation
        prepareJob?.cancel()
        val old = detach()
        platform.keepAwake(false)
        old?.server?.close()
        old?.session?.let { s -> cleanupScope.launch { withTimeoutOrNull(timing.closeStopTimeoutMs * 2) { s.close(stopRenderer = true) } } }
        if (_state.value.phase !is CastPhase.Failed) _state.value = CoordinatorState(CastPhase.Idle)
    }

    private suspend fun prepare(gen: Long, items: List<T>, unreadable: Int) {
        val lan = platform.lanAddress() ?: return fail(gen, CastFailure.NoNetwork)
        val readable = items.mapNotNull { item ->
            try {
                platform.inspect(item)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        if (gen != generation) return
        val shared = items.size + unreadable
        val skipped = shared - readable.size
        if (readable.isEmpty()) return fail(gen, CastFailure.NothingReadable(shared))
        val server = try {
            platform.startServer(readable, lan)
        } catch (e: Exception) {
            return fail(gen, CastFailure.ServerFailed(e.message ?: e.javaClass.simpleName))
        }
        if (gen != generation) {
            server.close()
            return
        }
        val res = Resources(server, readable)
        current = res
        val name = platform.rememberedName()
        if (name != null) {
            _state.value = CoordinatorState(CastPhase.Connecting(name), skipped)
            val device = platform.resolveRemembered(lan)
            if (gen != generation) return
            if (device != null) {
                startSession(res, device)
                return
            }
        }
        _state.value = CoordinatorState(CastPhase.ChooseRenderer(readable.size), skipped)
    }

    private fun startSession(res: Resources, device: RendererDevice) {
        platform.remember(device)
        val queue = res.items.mapIndexed { i, item ->
            QueueEntry(item.title, res.server.urlFor(i), item.mime, item.kind, item.source.length)
        }
        val session = CastSession(platform.client(device), queue, scope, timing)
        res.session = session
        session.start()
        _state.value = CoordinatorState(CastPhase.Casting(session, device), _state.value.skipped)
        platform.keepAwake(true)
    }

    private fun fail(gen: Long, failure: CastFailure) {
        if (gen != generation) return
        detach()?.let { release(it, stopRenderer = false) }
        platform.keepAwake(false)
        _state.value = CoordinatorState(CastPhase.Failed(failure))
        platform.finished()
    }

    private fun detach(): Resources? = current.also { current = null }

    private fun release(res: Resources, stopRenderer: Boolean) {
        res.server.close()
        res.session?.let { s -> scope.launch { s.close(stopRenderer) } }
    }
}
