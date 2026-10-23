package io.github.nezdelion.dlnatvcast.core.upnp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Turns SSDP answers into usable renderers and finds a remembered renderer again. */
class RendererDiscovery(
    private val ssdp: SsdpClient,
    private val fetch: (String) -> RendererDevice = { DeviceDescription.fetch(it) },
) {
    /** Emits each renderer with AVTransport once (by UDN), as soon as its description is read. */
    fun discover(): Flow<RendererDevice> = channelFlow {
        val seenUdn = mutableSetOf<String>()
        ssdp.search().collect { response ->
            if (response.udn != null && response.udn in seenUdn) return@collect
            launch(Dispatchers.IO) {
                val device = try {
                    fetch(response.location)
                } catch (_: IOException) {
                    return@launch
                } catch (_: IllegalArgumentException) {
                    // Malformed URL in a foreign SSDP reply.
                    return@launch
                }
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
            if (location != null) {
                val device = try {
                    fetch(location)
                } catch (_: IOException) {
                    null
                }
                if (device?.udn == udn) emit(device)
            }
        }.flowOn(Dispatchers.IO)
        return withTimeoutOrNull(timeoutMs) {
            merge(direct, discover()).firstOrNull { it.udn == udn }
        }
    }
}
