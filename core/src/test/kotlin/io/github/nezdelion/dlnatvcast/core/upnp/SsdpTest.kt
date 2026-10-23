package io.github.nezdelion.dlnatvcast.core.upnp

import io.github.nezdelion.dlnatvcast.core.Fixtures
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SsdpTest {
    private val captured = Fixtures.text("tv-renderer-ssdp-response.txt")

    @Test
    fun `parses tv-renderer response`() {
        val r = SsdpResponse.parse(captured.toByteArray())!!
        assertEquals("http://192.168.50.23:49494/description.xml", r.location)
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", r.searchTarget)
        assertEquals("uuid:6f1c2a3b-4d5e-5f60-8a7b-9c0d1e2f3a4b", r.udn)
        assertEquals("Linux UPnP/1.0 tv-renderer/1.0", r.server)
    }

    @Test
    fun `tolerates bare LF and lowercase headers`() {
        val text = "HTTP/1.1 200 OK\nlocation: http://10.0.0.5:1400/xml/device_description.xml\nusn: uuid:abc\n\n"
        val r = SsdpResponse.parse(text.toByteArray())!!
        assertEquals("http://10.0.0.5:1400/xml/device_description.xml", r.location)
        assertEquals("uuid:abc", r.udn)
    }

    @Test
    fun `rejects notifications, searches and missing location`() {
        assertNull(SsdpResponse.parse("NOTIFY * HTTP/1.1\r\nLOCATION: http://a/\r\n\r\n".toByteArray()))
        assertNull(SsdpResponse.parse(String(SsdpClient.mSearch("ssdp:all", 2)).toByteArray()))
        assertNull(SsdpResponse.parse("HTTP/1.1 200 OK\r\nST: x\r\n\r\n".toByteArray()))
        assertNull(SsdpResponse.parse("HTTP/1.1 404 Not Found\r\nLOCATION: http://a/\r\n\r\n".toByteArray()))
        assertNull(SsdpResponse.parse("garbage".toByteArray()))
    }

    @Test
    fun `M-SEARCH message is well formed`() {
        val text = String(SsdpClient.mSearch(SsdpClient.MEDIA_RENDERER, 2))
        assertTrue(text.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(text.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue(text.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(text.contains("MX: 2\r\n"))
        assertTrue(text.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"))
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test
    fun `fake transport - sends three times, emits each location once`() = runBlocking {
        val transport = FakeTransport()
        val results = SsdpClient { transport }.search(mxSeconds = 0, repeats = 3, repeatIntervalMs = 100).toList()
        assertEquals(3, transport.sent.size)
        assertEquals(
            listOf("http://192.168.50.23:49494/description.xml", "http://192.168.50.40:8080/dd.xml"),
            results.map { it.location },
        )
        assertTrue(transport.closed)
    }

    @Test
    fun `real UDP socket against a fake responder`() = runBlocking {
        val responder = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val searches = LinkedBlockingQueue<String>()
        val worker = thread {
            val buf = ByteArray(2048)
            try {
                while (true) {
                    val packet = DatagramPacket(buf, buf.size)
                    responder.receive(packet)
                    searches += String(packet.data, 0, packet.length)
                    val reply = captured.toByteArray()
                    responder.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                }
            } catch (_: Exception) {
            }
        }
        val target = InetSocketAddress(InetAddress.getLoopbackAddress(), responder.localPort)
        val results = SsdpClient { UdpSsdpTransport(target = target) }
            .search(mxSeconds = 0, repeats = 2, repeatIntervalMs = 100).toList()
        responder.close()
        worker.join()
        assertEquals(listOf("uuid:6f1c2a3b-4d5e-5f60-8a7b-9c0d1e2f3a4b"), results.map { it.udn })
        assertTrue(searches.poll(1, TimeUnit.SECONDS)!!.startsWith("M-SEARCH"))
    }

    private inner class FakeTransport : SsdpTransport {
        val sent = mutableListOf<String>()
        private val inbox = ArrayDeque<ByteArray>()
        var closed = false

        override fun send(packet: ByteArray) {
            sent += String(packet)
            // Every search gets the same renderer again plus noise; the second device answers once.
            inbox += captured.toByteArray()
            inbox += "NOTIFY * HTTP/1.1\r\nLOCATION: http://x/\r\n\r\n".toByteArray()
            if (sent.size == 2) {
                inbox += "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.50.40:8080/dd.xml\r\nUSN: uuid:other\r\n\r\n".toByteArray()
            }
        }

        override fun receive(timeoutMs: Int): ByteArray? {
            if (inbox.isEmpty()) {
                Thread.sleep(timeoutMs.toLong())
                return null
            }
            return inbox.removeFirst()
        }

        override fun close() {
            closed = true
        }
    }
}
