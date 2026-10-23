package io.github.nezdelion.dlnatvcast.core.session

import io.github.nezdelion.dlnatvcast.core.MediaKind
import io.github.nezdelion.dlnatvcast.core.upnp.DidlLite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** One queue entry as the renderer sees it: a URL on the phone's HTTP server plus metadata. */
data class QueueEntry(
    val title: String,
    val url: String,
    val mime: String,
    val kind: MediaKind,
    val size: Long?,
)

data class SessionState(
    val rendererName: String,
    val queue: List<QueueEntry>,
    val index: Int = 0,
    val transport: TransportState = TransportState.TRANSITIONING,
    val positionSec: Long? = null,
    val durationSec: Long? = null,
    val hasVolume: Boolean = false,
    val volume: Int? = null,
    val muted: Boolean? = null,
    /** Last command or renderer error, shown until the next successful command. */
    val error: String? = null,
    val rendererLost: Boolean = false,
    val closed: Boolean = false,
) {
    val current: QueueEntry get() = queue[index]
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index < queue.lastIndex
    val canSeek: Boolean get() = current.kind != MediaKind.IMAGE && durationSec != null
}

data class SessionTiming(
    val foregroundPollMs: Long = 1_000,
    val backgroundPollMs: Long = 5_000,
    val lostAfterMs: Long = 15_000,
    /** Position this close to the duration counts as "finished". */
    val endToleranceSec: Long = 3,
    val volumeRefreshEvery: Int = 5,
    val closeStopTimeoutMs: Long = 3_000,
)

/**
 * Plays a fixed queue on one renderer, polls it and advances on natural end (design §5).
 * All renderer traffic for commands and polls is serialised through one mutex.
 */
class CastSession(
    private val renderer: RendererClient,
    queue: List<QueueEntry>,
    private val scope: CoroutineScope,
    private val timing: SessionTiming = SessionTiming(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    init {
        require(queue.isNotEmpty()) { "Empty queue" }
    }

    private val _state = MutableStateFlow(
        SessionState(rendererName = renderer.name, queue = queue, hasVolume = renderer.hasRenderingControl),
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var pollJob: Job? = null
    @Volatile private var foreground = true

    // Natural-end detection, reset for every item.
    private var sawPlaying = false
    private var userStopped = false
    private var lastPositionSec: Long? = null
    private var lastPlayingAtMs: Long? = null

    private var firstFailureAtMs: Long? = null
    private var pollCount = 0

    /** Starts polling and plays [startIndex]. */
    fun start(startIndex: Int = 0) {
        check(pollJob == null) { "Already started" }
        pollJob = scope.launch { pollLoop() }
        scope.launch {
            mutex.withLock { playLocked(startIndex) }
            refreshVolume()
        }
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        wake.trySend(Unit)
    }

    suspend fun playItem(index: Int) = mutex.withLock { playLocked(index) }

    suspend fun next() = mutex.withLock {
        if (_state.value.hasNext) playLocked(_state.value.index + 1)
    }

    suspend fun previous() = mutex.withLock {
        if (_state.value.hasPrevious) playLocked(_state.value.index - 1)
    }

    suspend fun pause() = command {
        renderer.pause()
        _state.update { it.copy(transport = TransportState.PAUSED_PLAYBACK) }
    }

    /** Resumes after pause, or plays the current item again after a stop. */
    suspend fun resume() {
        val transport = _state.value.transport
        if (transport == TransportState.NO_MEDIA_PRESENT) {
            playItem(_state.value.index)
            return
        }
        command {
            if (transport == TransportState.STOPPED) resetEndDetection()
            userStopped = false
            renderer.play()
            _state.update { it.copy(transport = TransportState.PLAYING) }
        }
    }

    suspend fun togglePlayPause() {
        if (_state.value.transport == TransportState.PLAYING) pause() else resume()
    }

    suspend fun stop() = command {
        userStopped = true
        renderer.stop()
        _state.update { it.copy(transport = TransportState.STOPPED, positionSec = 0) }
    }

    suspend fun seek(positionSec: Long) = command {
        val target = positionSec.coerceAtLeast(0)
        renderer.seek(target)
        lastPositionSec = target
        lastPlayingAtMs = if (_state.value.transport == TransportState.PLAYING) now() else null
        _state.update { it.copy(positionSec = target) }
    }

    suspend fun setVolume(volume: Int) = command {
        val v = volume.coerceIn(0, 100)
        renderer.setVolume(v)
        _state.update { it.copy(volume = v) }
    }

    suspend fun changeVolume(delta: Int) = setVolume((_state.value.volume ?: 0) + delta)

    suspend fun toggleMute() = command {
        val target = !(_state.value.muted ?: false)
        renderer.setMuted(target)
        _state.update { it.copy(muted = target) }
    }

    /** User asked to try again after "connection lost". */
    fun retry() {
        firstFailureAtMs = null
        _state.update { it.copy(rendererLost = false, error = null) }
        wake.trySend(Unit)
    }

    /** Ends the session; optionally sends Stop to the renderer first. */
    suspend fun close(stopRenderer: Boolean = true) {
        pollJob?.cancel()
        if (stopRenderer) {
            withTimeoutOrNull(timing.closeStopTimeoutMs) {
                try {
                    renderer.stop()
                } catch (_: IOException) {
                }
            }
        }
        _state.update { it.copy(closed = true, transport = TransportState.STOPPED) }
    }

    private suspend fun command(block: suspend () -> Unit) = mutex.withLock {
        try {
            block()
            _state.update { it.copy(error = null) }
        } catch (e: IOException) {
            _state.update { it.copy(error = e.message ?: "Renderer error") }
        }
    }

    private suspend fun playLocked(index: Int) {
        val entry = _state.value.queue.getOrNull(index) ?: return
        resetEndDetection()
        userStopped = false
        _state.update {
            it.copy(index = index, transport = TransportState.TRANSITIONING, positionSec = null, durationSec = null, error = null)
        }
        try {
            renderer.setUri(entry.url, DidlLite.item(entry.title, entry.url, entry.mime, entry.kind, entry.size))
            renderer.play()
        } catch (e: IOException) {
            // No silent retry: the user sees the fault and decides.
            _state.update { it.copy(transport = TransportState.STOPPED, error = e.message ?: "Renderer error") }
        }
    }

    private fun resetEndDetection() {
        sawPlaying = false
        lastPositionSec = null
        lastPlayingAtMs = null
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            mutex.withLock { pollLocked() }
            if (pollCount % timing.volumeRefreshEvery == 0) refreshVolume()
            val interval = if (foreground) timing.foregroundPollMs else timing.backgroundPollMs
            withTimeoutOrNull(interval) { wake.receive() }
        }
    }

    private suspend fun refreshVolume() {
        if (!renderer.hasRenderingControl) return
        try {
            val volume = renderer.volume()
            val muted = renderer.muted()
            _state.update { it.copy(volume = volume, muted = muted) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            // Volume is cosmetic; transport polling reports connectivity.
        }
    }

    private suspend fun pollLocked() {
        pollCount++
        val transport: TransportState
        val position: PositionInfo
        try {
            transport = renderer.transportState()
            position = renderer.positionInfo()
        } catch (_: IOException) {
            val t = now()
            val since = firstFailureAtMs ?: t.also { firstFailureAtMs = it }
            if (t - since >= timing.lostAfterMs) _state.update { it.copy(rendererLost = true) }
            return
        }
        firstFailureAtMs = null
        if (_state.value.rendererLost) _state.update { it.copy(rendererLost = false) }
        onPoll(transport, position)
    }

    private suspend fun onPoll(transport: TransportState, position: PositionInfo) {
        val state = _state.value
        when (transport) {
            TransportState.PLAYING -> {
                sawPlaying = true
                lastPositionSec = position.positionSec
                lastPlayingAtMs = now()
                _state.update {
                    it.copy(transport = transport, positionSec = position.positionSec, durationSec = position.durationSec ?: it.durationSec)
                }
            }

            TransportState.PAUSED_PLAYBACK -> {
                lastPositionSec = position.positionSec ?: lastPositionSec
                lastPlayingAtMs = null
                _state.update {
                    it.copy(transport = transport, positionSec = position.positionSec, durationSec = position.durationSec ?: it.durationSec)
                }
            }

            TransportState.STOPPED, TransportState.NO_MEDIA_PRESENT -> {
                val finished = sawPlaying && !userStopped && state.current.kind != MediaKind.IMAGE && reachedEnd(state)
                resetEndDetection()
                if (finished && state.hasNext) {
                    playLocked(state.index + 1)
                } else {
                    _state.update { it.copy(transport = transport, positionSec = 0) }
                }
            }

            // Loading or unknown: keep the last displayed state.
            TransportState.TRANSITIONING, TransportState.UNKNOWN -> Unit
        }
    }

    /**
     * Whether a stop after playing looks like the natural end of the item. The last polled
     * position is projected forward by the time since that poll, so the slower background
     * interval does not hide an end that happened between polls.
     */
    private fun reachedEnd(state: SessionState): Boolean {
        val duration = state.durationSec ?: return true
        val position = lastPositionSec ?: return false
        val elapsedSec = lastPlayingAtMs?.let { (now() - it) / 1000 } ?: 0
        return position + elapsedSec >= duration - timing.endToleranceSec
    }
}
