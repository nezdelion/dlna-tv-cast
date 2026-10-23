package io.github.nezdelion.dlnatvcast.core.upnp

import io.github.nezdelion.dlnatvcast.core.session.TransportState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class SoapTest {
    private val avt = RendererDevice.AV_TRANSPORT_V1

    private fun envelope(inner: String) =
        """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>$inner</s:Body></s:Envelope>"""

    private fun fault(code: Int, description: String) = envelope(
        "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode>" +
            "<errorDescription>$description</errorDescription></UPnPError></detail></s:Fault>",
    )

    @Test
    fun `envelope escapes arguments`() {
        val xml = SoapClient.envelope(avt, "SetAVTransportURI", listOf("InstanceID" to "0", "CurrentURIMetaData" to "<a b=\"c\">&'</a>"))
        assertTrue(xml.contains("<u:SetAVTransportURI xmlns:u=\"$avt\">"))
        assertTrue(xml.contains("<InstanceID>0</InstanceID>"))
        assertTrue(xml.contains("<CurrentURIMetaData>&lt;a b=&quot;c&quot;&gt;&amp;&apos;&lt;/a&gt;</CurrentURIMetaData>"))
        // Round-trips through a real parser.
        val parsed = Xml.parse(xml).documentElement.child("Body")!!.child("SetAVTransportURI")!!
        assertEquals("<a b=\"c\">&'</a>", parsed.childText("CurrentURIMetaData"))
    }

    @Test
    fun `out arguments parsed including escaped metadata`() {
        val body = envelope(
            "<u:GetPositionInfoResponse xmlns:u=\"$avt\"><Track>1</Track><TrackDuration>0:01:05</TrackDuration>" +
                "<TrackMetaData>&lt;DIDL-Lite&gt;</TrackMetaData><RelTime>0:00:07</RelTime></u:GetPositionInfoResponse>",
        )
        val out = SoapClient.parseResponse(200, body, "GetPositionInfo")
        assertEquals("0:01:05", out["TrackDuration"])
        assertEquals("<DIDL-Lite>", out["TrackMetaData"])
        assertEquals("0:00:07", out["RelTime"])
    }

    @Test
    fun `UPnPError maps to UpnpException`() {
        val e = assertThrows<UpnpException> { SoapClient.parseResponse(500, fault(714, "Illegal MIME-type"), "SetAVTransportURI") }
        assertEquals(714, e.code)
        assertEquals("Illegal MIME-type", e.description)
        assertEquals("UPnP error 714: Illegal MIME-type", e.message)
    }

    @Test
    fun `non-SOAP errors are IOExceptions`() {
        assertThrows<IOException> { SoapClient.parseResponse(404, "", "Play") }
        assertThrows<IOException> { SoapClient.parseResponse(200, "<html/>", "Play") }
        val e = assertThrows<IOException> { SoapClient.parseResponse(500, "oops", "Play") }
        assertTrue(e !is UpnpException)
    }

    @Test
    fun `renderer client over HTTP`() = runBlocking {
        TestHttpServer().start().use { http ->
            http.respond(
                "/avt", 200,
                envelope("<u:GetTransportInfoResponse xmlns:u=\"$avt\"><CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState></u:GetTransportInfoResponse>"),
            )
            http.respond("/avt-fault", 500, fault(701, "Transition not available"))
            http.respond(
                "/rcs", 200,
                envelope("<u:GetVolumeResponse xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\"><CurrentVolume>42</CurrentVolume></u:GetVolumeResponse>"),
            )
            val device = RendererDevice("uuid:x", "TV", "${http.base}/d.xml", "${http.base}/avt", "${http.base}/rcs")
            val client = UpnpRendererClient(device)
            assertEquals(TransportState.PAUSED_PLAYBACK, client.transportState())
            assertEquals(42, client.volume())

            val req = http.requests.first()
            assertEquals("POST", req.method)
            assertEquals("\"$avt#GetTransportInfo\"", req.headers["soapaction"])
            assertTrue(req.headers["content-type"]!!.startsWith("text/xml"))
            assertTrue(req.body.contains("<InstanceID>0</InstanceID>"))
            assertTrue(http.requests[1].body.contains("<Channel>Master</Channel>"))

            val failing = UpnpRendererClient(device.copy(avTransportControlUrl = "${http.base}/avt-fault"))
            val e = assertThrows<UpnpException> { runBlocking { failing.play() } }
            assertEquals(701, e.code)
            assertTrue(http.requests.last().body.contains("<Speed>1</Speed>"))
        }
    }

    @Test
    fun `time format and parse`() {
        assertEquals("0:01:30", UpnpTime.format(90))
        assertEquals("1:00:05", UpnpTime.format(3605))
        assertEquals(90L, UpnpTime.parse("0:01:30"))
        assertEquals(90L, UpnpTime.parse("00:01:30.500"))
        assertEquals(36005L, UpnpTime.parse("10:00:05"))
        assertNull(UpnpTime.parse("NOT_IMPLEMENTED"))
        assertNull(UpnpTime.parse(""))
        assertNull(UpnpTime.parse("1:99:00"))
    }
}
