package io.github.nezdelion.dlnatvcast.core.upnp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Turns SSDP answers into usable renderers and finds a remembered renderer again. */
class RendererDiscovery(
    private val ssdp: SsdpClient,
    private val fetch: suspend (String) -> RendererDevice = { DeviceDescription.fetch(it) },
) {
    /** Emits each renderer with AVTransport once (by UDN), as soon as its description is read. */
    fun discover(): Flow<RendererDevice> = channelFlow {
        val seenUdn = mutableSetOf<String>()
        // Any device on the LAN can answer SSDP; bound how many descriptions are fetched at once.
        val fetches = Semaphore(MAX_PARALLEL_FETCHES)
        ssdp.search().collect { response ->
            if (response.udn != null && synchronized(seenUdn) { response.udn in seenUdn }) return@collect
            launch {
                val device = fetches.withPermit { fetchOrNull(response.location) } ?: return@launch
                val fresh = synchronized(seenUdn) { seenUdn.add(device.udn) }
                if (fresh) send(device)
            }
        }
    }

    /**
     * Finds the renderer with [udn]: either its last [location] still answers with the same UDN,
     * or it answers discovery (possibly with a new address). Null when neither happens within
     * [timeoutMs].
     */
    suspend fun resolve(udn: String, location: String?, timeoutMs: Long = 3_000): RendererDevice? {
        val direct = flow {
            val device = location?.let { fetchOrNull(it) }
            if (device?.udn == udn) emit(device)
        }
        return withTimeoutOrNull(timeoutMs) {
            merge(direct, discover()).firstOrNull { it.udn == udn }
        }
    }

    /** A bad or hostile device is skipped; it must never take the caller down. */
    private suspend fun fetchOrNull(location: String): RendererDevice? = try {
        fetch(location)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MAX_PARALLEL_FETCHES = 4
    }
}
