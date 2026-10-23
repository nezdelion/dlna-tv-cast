package io.github.nezdelion.dlnatvcast.core.upnp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.Closeable
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

data class SsdpResponse(
    val location: String,
    val usn: String?,
    val searchTarget: String?,
    val server: String?,
) {
    /** `uuid:...` part of the USN. */
    val udn: String? get() = usn?.substringBefore("::")?.takeIf { it.startsWith("uuid:") }

    companion object {
        /** Parses an M-SEARCH unicast reply; returns null for anything else. */
        fun parse(packet: ByteArray, length: Int = packet.size): SsdpResponse? {
            val text = String(packet, 0, length, Charsets.UTF_8)
            val lines = text.split("\r\n", "\n")
            val status = lines.firstOrNull()?.trim() ?: return null
            if (!status.startsWith("HTTP/1.", ignoreCase = true)) return null
            if (status.split(' ').getOrNull(1) != "200") return null
            val headers = lines.drop(1)
                .filter { it.contains(':') }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val location = headers["location"]?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: return null
            return SsdpResponse(location, headers["usn"], headers["st"], headers["server"])
        }
    }
}

/** Datagram I/O used by [SsdpClient]; replaced by a fake in tests. */
interface SsdpTransport : Closeable {
    fun send(packet: ByteArray)

    /** Waits up to [timeoutMs] for one datagram; null on timeout. */
    fun receive(timeoutMs: Int): ByteArray?
}

/**
 * UDP transport that multicasts to the SSDP group (or [target]) through [networkInterface]
 * and receives unicast replies on the same socket.
 */
class UdpSsdpTransport(
    private val networkInterface: NetworkInterface? = null,
    private val target: InetSocketAddress = InetSocketAddress(InetAddress.getByName(SSDP_HOST), SSDP_PORT),
) : SsdpTransport {
    private val socket = MulticastSocket(0).apply {
        timeToLive = 2
        networkInterface?.let { this.networkInterface = it }
    }
    private val buffer = ByteArray(4096)

    override fun send(packet: ByteArray) {
        socket.send(DatagramPacket(packet, packet.size, target))
    }

    override fun receive(timeoutMs: Int): ByteArray? {
        socket.soTimeout = timeoutMs.coerceAtLeast(1)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            packet.data.copyOf(packet.length)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    override fun close() = socket.close()

    companion object {
        const val SSDP_HOST = "239.255.255.250"
        const val SSDP_PORT = 1900
    }
}

class SsdpClient(private val transportFactory: () -> SsdpTransport) {

    /**
     * Sends M-SEARCH [repeats] times and emits each distinct responder (by location) as soon as
     * it answers. Completes after [mxSeconds] + 1 s from the last send.
     */
    fun search(
        searchTarget: String = MEDIA_RENDERER,
        mxSeconds: Int = 2,
        repeats: Int = 3,
        repeatIntervalMs: Long = 500,
    ): Flow<SsdpResponse> = flow {
        val seen = mutableSetOf<String>()
        val message = mSearch(searchTarget, mxSeconds)
        transportFactory().use { transport ->
            val start = System.nanoTime()
            fun elapsedMs() = (System.nanoTime() - start) / 1_000_000
            val deadline = (repeats - 1) * repeatIntervalMs + (mxSeconds + 1) * 1000L
            var sent = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val now = elapsedMs()
                if (now >= deadline) break
                if (sent < repeats && now >= sent * repeatIntervalMs) {
                    transport.send(message)
                    sent++
                }
                val nextSend = if (sent < repeats) sent * repeatIntervalMs else deadline
                val wait = (minOf(nextSend, deadline) - elapsedMs()).coerceIn(1, 200).toInt()
                val packet = transport.receive(wait) ?: continue
                val response = SsdpResponse.parse(packet) ?: continue
                if (seen.add(response.location)) emit(response)
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

        fun mSearch(searchTarget: String, mxSeconds: Int): ByteArray = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: ${UdpSsdpTransport.SSDP_HOST}:${UdpSsdpTransport.SSDP_PORT}\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: $mxSeconds\r\n" +
                "ST: $searchTarget\r\n" +
                "USER-AGENT: Android/10 UPnP/1.0 dlna-tv-cast/1.0\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)
    }
}
