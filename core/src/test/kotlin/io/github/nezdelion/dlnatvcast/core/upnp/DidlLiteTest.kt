package io.github.nezdelion.dlnatvcast.core.upnp

import io.github.nezdelion.dlnatvcast.core.MediaKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DidlLiteTest {
    private fun parse(xml: String) = Xml.parse(xml).documentElement.child("item")!!

    @Test
    fun `classes by kind`() {
        assertEquals("object.item.imageItem.photo", DidlLite.upnpClass(MediaKind.IMAGE))
        assertEquals("object.item.videoItem", DidlLite.upnpClass(MediaKind.VIDEO))
        assertEquals("object.item.audioItem.musicTrack", DidlLite.upnpClass(MediaKind.AUDIO))
    }

    @Test
    fun `video item with size and protocolInfo`() {
        val xml = DidlLite.item("Clip", "http://10.0.0.2:4000/t/0/Clip.mp4", "video/mp4", MediaKind.VIDEO, 1234)
        val item = parse(xml)
        assertEquals("Clip", item.childText("title"))
        assertEquals("object.item.videoItem", item.childText("class"))
        val res = item.child("res")!!
        assertEquals("http://10.0.0.2:4000/t/0/Clip.mp4", res.textContent)
        assertEquals("1234", res.getAttribute("size"))
        assertEquals(
            "http-get:*:video/mp4:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
            res.getAttribute("protocolInfo"),
        )
    }

    @Test
    fun `titles and urls with special characters are escaped`() {
        val title = "Tom & Jerry <\"best\"> it's"
        val url = "http://h/t/0/a.jpg?x=1&y=2"
        val xml = DidlLite.item(title, url, "image/jpeg", MediaKind.IMAGE, null)
        assertFalse(xml.contains("Tom & Jerry"))
        val item = parse(xml)
        assertEquals(title, item.childText("title"))
        assertEquals(url, item.child("res")!!.textContent)
        assertNull(item.child("res")!!.getAttributeNode("size"))
    }
}
