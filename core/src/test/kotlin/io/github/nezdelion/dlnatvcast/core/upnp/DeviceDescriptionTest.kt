package io.github.nezdelion.dlnatvcast.core.upnp

import io.github.nezdelion.dlnatvcast.core.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeviceDescriptionTest {
    @Test
    fun `tv-renderer description`() {
        val d = DeviceDescription.parse(
            Fixtures.text("tv-renderer-description.xml"),
            "http://192.168.50.23:49494/description.xml",
        )
        assertEquals("TV", d.friendlyName)
        assertEquals("uuid:6f1c2a3b-4d5e-5f60-8a7b-9c0d1e2f3a4b", d.udn)
        assertEquals("http://192.168.50.23:49494/control/AVTransport", d.avTransportControlUrl)
        assertEquals("http://192.168.50.23:49494/control/RenderingControl", d.renderingControlUrl)
        assertEquals("http://192.168.50.23:49494/description.xml", d.location)
    }

    @Test
    fun `vendor description with URLBase and embedded renderer`() {
        val d = DeviceDescription.parse(Fixtures.text("vendor-description.xml"), "http://192.168.1.50:9197/dmr")
        assertEquals("[TV] Living Room & Kitchen", d.friendlyName)
        assertEquals("uuid:renderer-1234", d.udn)
        assertEquals("http://192.168.1.50:7676/upnp/control/AVTransport1", d.avTransportControlUrl)
        assertEquals("http://192.168.1.50:7676/smp_15_", d.renderingControlUrl)
    }

    @Test
    fun `relative URL resolution`() {
        assertEquals("http://h:1/a/ctl", DeviceDescription.resolve("http://h:1/a/desc.xml", "ctl"))
        assertEquals("http://h:1/ctl", DeviceDescription.resolve("http://h:1/a/desc.xml", "/ctl"))
        assertEquals("http://h:1/ctl", DeviceDescription.resolve("http://h:1", "ctl"))
        assertEquals("http://o:2/x", DeviceDescription.resolve("http://h:1/", "http://o:2/x"))
    }

    @Test
    fun `renderer without RenderingControl`() {
        val xml = """<root xmlns="urn:schemas-upnp-org:device-1-0"><device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:2</deviceType><UDN>uuid:x</UDN>
            <serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:2</serviceType>
            <controlURL>/avt</controlURL></service></serviceList></device></root>"""
        val d = DeviceDescription.parse(xml, "http://h:1/d.xml")
        assertNull(d.renderingControlUrl)
        assertEquals("uuid:x", d.friendlyName)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:2", d.avTransportType)
    }

    @Test
    fun `rejects media servers, renderers without AVTransport and garbage`() {
        val server = """<root xmlns="urn:schemas-upnp-org:device-1-0"><device>
            <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType><UDN>uuid:s</UDN>
            <serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
            <controlURL>/cd</controlURL></service></serviceList></device></root>"""
        val noAvt = """<root xmlns="urn:schemas-upnp-org:device-1-0"><device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><UDN>uuid:r</UDN>
            <serviceList/></device></root>"""
        assertThrows<InvalidRendererException> { DeviceDescription.parse(server, "http://h/") }
        assertThrows<InvalidRendererException> { DeviceDescription.parse(noAvt, "http://h/") }
        assertThrows<InvalidRendererException> { DeviceDescription.parse("<html>nope", "http://h/") }
        assertThrows<InvalidRendererException> {
            DeviceDescription.parse("<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>", "http://h/")
        }
    }

    @Test
    fun `fetch validates over HTTP`() = runBlocking {
        TestHttpServer().start().use { http ->
            http.respond("/description.xml", 200, Fixtures.text("tv-renderer-description.xml"))
            http.respond("/missing", 404, "")
            val d = DeviceDescription.fetch("${http.base}/description.xml")
            assertEquals("TV", d.friendlyName)
            assertEquals("${http.base}/control/AVTransport", d.avTransportControlUrl)
            assertThrows<InvalidRendererException> { runBlocking { DeviceDescription.fetch("${http.base}/missing") } }
            assertThrows<InvalidRendererException> { runBlocking { DeviceDescription.fetch("ftp://x/y") } }
            assertThrows<InvalidRendererException> { runBlocking { DeviceDescription.fetch("not a url") } }
            assertThrows<InvalidRendererException> { runBlocking { DeviceDescription.fetch("http://127.0.0.1:1/description.xml", 500) } }
        }
    }
}
