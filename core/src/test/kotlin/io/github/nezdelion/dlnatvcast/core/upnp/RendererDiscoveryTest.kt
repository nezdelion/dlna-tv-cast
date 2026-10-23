package io.github.nezdelion.dlnatvcast.core.upnp

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RendererDiscoveryTest {
    private fun device(udn: String, location: String) =
        RendererDevice(udn, "TV $udn", location, "$location/avt", null)

    /** Answers every search with the given (location, usn) pairs. */
    private class ScriptedTransport(private val answers: List<Pair<String, String>>) : SsdpTransport {
        private val inbox = ArrayDeque<ByteArray>()
        override fun send(packet: ByteArray) {
            answers.forEach { (loc, usn) -> inbox += "HTTP/1.1 200 OK\r\nLOCATION: $loc\r\nUSN: $usn::urn:x\r\n\r\n".toByteArray() }
        }
        override fun receive(timeoutMs: Int): ByteArray? =
            inbox.removeFirstOrNull() ?: run { Thread.sleep(timeoutMs.toLong()); null }
        override fun close() {}
    }

    private fun discovery(answers: List<Pair<String, String>>, fetch: (String) -> RendererDevice) =
        RendererDiscovery(SsdpClient { ScriptedTransport(answers) }, fetch)

    @Test
    fun `lists renderers once and skips devices that fail validation`() = runBlocking {
        val d = discovery(
            listOf("http://a/d.xml" to "uuid:a", "http://b/d.xml" to "uuid:b", "http://srv/d.xml" to "uuid:srv"),
        ) { loc ->
            when (loc) {
                "http://srv/d.xml" -> throw InvalidRendererException("Not a MediaRenderer with AVTransport")
                else -> device(if (loc.contains("//a/")) "uuid:a" else "uuid:b", loc)
            }
        }
        val found = d.discover().toList()
        assertEquals(setOf("uuid:a", "uuid:b"), found.map { it.udn }.toSet())
        assertEquals(2, found.size)
    }

    @Test
    fun `resolve uses the remembered location when it still answers`() = runBlocking {
        val d = discovery(emptyList()) { loc -> device("uuid:tv", loc) }
        val start = System.nanoTime()
        val r = d.resolve("uuid:tv", "http://192.168.50.23:49494/description.xml")
        assertEquals("http://192.168.50.23:49494/description.xml", r!!.location)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_500)
    }

    @Test
    fun `resolve follows a new address announced with the same UDN`() = runBlocking {
        val d = discovery(listOf("http://192.168.50.77:49494/description.xml" to "uuid:tv")) { loc ->
            if (loc.contains("192.168.50.23")) throw InvalidRendererException("No response") else device("uuid:tv", loc)
        }
        val r = d.resolve("uuid:tv", "http://192.168.50.23:49494/description.xml")
        assertEquals("http://192.168.50.77:49494/description.xml", r!!.location)
    }

    @Test
    fun `resolve ignores a different device at the old address`() = runBlocking {
        val d = discovery(emptyList()) { loc -> device("uuid:other", loc) }
        assertNull(d.resolve("uuid:tv", "http://192.168.50.23:49494/description.xml", timeoutMs = 1_000))
    }
}
