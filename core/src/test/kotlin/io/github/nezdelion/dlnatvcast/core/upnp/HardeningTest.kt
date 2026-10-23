package io.github.nezdelion.dlnatvcast.core.upnp

import io.github.nezdelion.dlnatvcast.core.Fixtures
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.Locale

/** Regression tests for review findings #3, #7, #9, #11 and #12. */
class HardeningTest {
    private val description = Fixtures.text("tv-renderer-description.xml")
    private val location = "http://192.168.50.23:49494/description.xml"

    private fun elapsedMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    // #3: cancellation and total deadlines must abort blocking HTTP.

    @Test
    fun `cancelling a SOAP call to a hung renderer returns promptly`() {
        StuckServer().use { stuck ->
            val client = UpnpRendererClient(
                RendererDevice("uuid:x", "TV", "${stuck.base}/d.xml", "${stuck.base}/avt", null),
                SoapClient(timeoutMs = 10_000),
            )
            val ms = elapsedMs {
                assertThrows<TimeoutCancellationException> { runBlocking { withTimeout(100) { client.stop() } } }
            }
            assertTrue(ms < 1_000, "took $ms ms")
        }
    }

    @Test
    fun `SOAP timeout is a total deadline and an IOException`() {
        TrickleServer().use { stuck ->
            val client = UpnpRendererClient(
                RendererDevice("uuid:x", "TV", "${stuck.base}/d.xml", "${stuck.base}/avt", null),
                SoapClient(timeoutMs = 300),
            )
            val ms = elapsedMs { assertThrows<IOException> { runBlocking { client.transportState() } } }
            assertTrue(ms < 1_500, "took $ms ms")
        }
    }

    @Test
    fun `resolve honours its budget when the remembered location hangs`() {
        StuckServer().use { stuck ->
            val discovery = RendererDiscovery(SsdpClient { SilentTransport() })
            var result: RendererDevice? = RendererDevice("x", "x", "x", "x", null)
            val ms = elapsedMs { result = runBlocking { discovery.resolve("uuid:tv", "${stuck.base}/description.xml", 300) } }
            assertNull(result)
            assertTrue(ms < 1_000, "took $ms ms")
        }
    }

    // #7: malformed URLs in a description are an invalid renderer, not a crash.

    @Test
    fun `malformed URLBase is rejected as invalid renderer`() {
        val xml = description.replace("<device>", "<URLBase>http://[invalid</URLBase><device>")
        assertThrows<InvalidRendererException> { DeviceDescription.parse(xml, location) }
    }

    @Test
    fun `control URLs must be usable http URLs`() {
        for (bad in listOf("ftp://192.168.50.23/avt", "https://192.168.50.23/avt", "http:///avt", "http://[::1/avt")) {
            val xml = description.replace("<controlURL>/control/AVTransport</controlURL>", "<controlURL>$bad</controlURL>")
            assertThrows<InvalidRendererException>(bad) { DeviceDescription.parse(xml, location) }
        }
    }

    @Test
    fun `discovery skips a device with a malformed description and keeps the collector alive`() = runBlocking {
        TestHttpServer().start().use { http ->
            http.respond("/good.xml", 200, description)
            http.respond("/bad.xml", 200, description.replace("<device>", "<URLBase>http://[invalid</URLBase><device>").replace("uuid:09cc", "uuid:bad0"))
            val transport = ScriptedTransport(listOf("${http.base}/bad.xml" to "uuid:bad", "${http.base}/good.xml" to "uuid:good"))
            val found = RendererDiscovery(SsdpClient { transport }).discover().toList()
            assertEquals(listOf("TV"), found.map { it.friendlyName })
        }
    }

    // #9: hostile XML is a protocol error, not a StackOverflowError or OOM.

    @Test
    fun `deeply nested description is rejected`() {
        val deep = "<a>".repeat(10_000) + "</a>".repeat(10_000)
        val xml = description.replace("<serviceList>", "<x>$deep</x><serviceList>")
        assertThrows<InvalidRendererException> { DeviceDescription.parse(xml, location) }
    }

    @Test
    fun `too many elements are rejected`() {
        val many = "<i/>".repeat(50_000)
        assertThrows<InvalidRendererException> { DeviceDescription.parse(description.replace("<serviceList>", "<x>$many</x><serviceList>"), location) }
    }

    @Test
    fun `oversized description body is rejected without reading it all`() {
        TestHttpServer().start().use { http ->
            val padding = "<!-- " + "x".repeat(2 * 1024 * 1024) + " -->"
            http.respond("/big.xml", 200, description.replace("<device>", "$padding<device>"))
            assertThrows<InvalidRendererException> { runBlocking { DeviceDescription.fetch("${http.base}/big.xml") } }
        }
    }

    @Test
    fun `chunked SOAP responses are decoded`() {
        java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress()).use { server ->
            kotlin.concurrent.thread(isDaemon = true) {
                server.accept().use { s ->
                    s.getInputStream().read(ByteArray(8192))
                    val body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
                        "<u:GetTransportInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                        "<CurrentTransportState>PLAYING</CurrentTransportState></u:GetTransportInfoResponse></s:Body></s:Envelope>"
                    val half = body.length / 2
                    val chunks = "%x\r\n%s\r\n%x\r\n%s\r\n0\r\n\r\n".format(half, body.substring(0, half), body.length - half, body.substring(half))
                    s.getOutputStream().write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n$chunks").toByteArray())
                }
            }
            val base = "http://127.0.0.1:${server.localPort}"
            val client = UpnpRendererClient(RendererDevice("uuid:x", "TV", "$base/d", "$base/avt", null))
            assertEquals(io.github.nezdelion.dlnatvcast.core.session.TransportState.PLAYING, runBlocking { client.transportState() })
        }
    }

    @Test
    fun `oversized SOAP response is an IOException`() {
        TestHttpServer().start().use { http ->
            http.respond(
                "/avt", 200,
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><!-- " + "x".repeat(2 * 1024 * 1024) + " -->" +
                    "<u:GetTransportInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                    "<CurrentTransportState>PLAYING</CurrentTransportState></u:GetTransportInfoResponse></s:Body></s:Envelope>",
            )
            val client = UpnpRendererClient(RendererDevice("uuid:x", "TV", "${http.base}/d", "${http.base}/avt", null))
            assertThrows<IOException> { runBlocking { client.transportState() } }
        }
    }

    // #11: DTDs are refused by an explicit, parser-independent check.

    @Test
    fun `valid renderer description with a DOCTYPE is rejected`() {
        val cases = listOf(
            "<!DOCTYPE root>",
            "<!DOCTYPE root [<!ENTITY name \"TV\">]>",
            "<!DOCTYPE root SYSTEM \"http://example.invalid/evil.dtd\">",
            "<!DOCTYPE root [<!ENTITY ext SYSTEM \"file:///etc/hostname\">]>",
        )
        for (doctype in cases) {
            val xml = description.replaceFirst("?>", "?>$doctype")
            val e = assertThrows<InvalidRendererException>(doctype) { DeviceDescription.parse(xml, location) }
            assertTrue(e.cause?.message.orEmpty().contains("DOCTYPE"), "$doctype -> ${e.message} / ${e.cause}")
        }
    }

    @Test
    fun `structure check refuses DTDs independently of the DOM parser`() {
        // Android's DocumentBuilderFactory ignores the hardening features, so the check must not rely on them.
        assertThrows<IllegalArgumentException> { Xml.checkStructure("<?xml version=\"1.0\"?><!DOCTYPE r><r/>") }
        assertThrows<IllegalArgumentException> { Xml.checkStructure("<r><!ENTITY x \"y\"></r>") }
        Xml.checkStructure("<?xml version=\"1.0\"?><!-- c --><r a=\"<x>\"><![CDATA[<!DOCTYPE>]]><s/></r>")
    }

    @Test
    fun `SOAP response with a DOCTYPE is rejected`() {
        val body = "<!DOCTYPE s [<!ENTITY x \"PLAYING\">]><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<u:GetTransportInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><CurrentTransportState>&x;</CurrentTransportState>" +
            "</u:GetTransportInfoResponse></s:Body></s:Envelope>"
        assertThrows<IOException> { SoapClient.parseResponse(200, body, "GetTransportInfo") }
    }

    @Test
    fun `comments, CDATA and quoted angle brackets still parse`() {
        val xml = description.replace("<device>", "<!-- <device> --><device>")
            .replace("<friendlyName>TV</friendlyName>", "<friendlyName><![CDATA[TV <1>]]></friendlyName>")
        assertEquals("TV <1>", DeviceDescription.parse(xml, location).friendlyName)
    }

    // #12: protocol values are ASCII whatever the default locale.

    @Test
    fun `UPnP time is formatted with ASCII digits in any locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("0:01:30", UpnpTime.format(90))
            assertEquals(90L, UpnpTime.parse("0:01:30"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    private class SilentTransport : SsdpTransport {
        override fun send(packet: ByteArray) {}
        override fun receive(timeoutMs: Int): ByteArray? {
            Thread.sleep(timeoutMs.toLong())
            return null
        }
        override fun close() {}
    }

    private class ScriptedTransport(private val answers: List<Pair<String, String>>) : SsdpTransport {
        private val inbox = ArrayDeque<ByteArray>()
        override fun send(packet: ByteArray) {
            answers.forEach { (loc, usn) -> inbox += "HTTP/1.1 200 OK\r\nLOCATION: $loc\r\nUSN: $usn::urn:x\r\n\r\n".toByteArray() }
        }
        override fun receive(timeoutMs: Int): ByteArray? =
            inbox.removeFirstOrNull() ?: run { Thread.sleep(timeoutMs.toLong()); null }
        override fun close() {}
    }
}
