package io.github.nezdelion.dlnatvcast.core.http

import io.github.nezdelion.dlnatvcast.core.MediaItem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import kotlin.random.Random

class MediaServerTest {
    private val photo = Random(1).nextBytes(10_000)
    private val video = Random(2).nextBytes(10 * 1024 * 1024)
    private val server = MediaServer(
        listOf(
            MediaItem("Holiday photo.jpg", ByteArraySource(photo, "image/jpeg")),
            MediaItem("clip.mp4", ByteArraySource(video, "video/mp4")),
            MediaItem("stream", UnknownLengthSource("audio/mpeg", byteArrayOf(1, 2, 3))),
        ),
        InetAddress.getLoopbackAddress(),
    ).start()

    @AfterEach
    fun tearDown() = server.close()

    private fun path(index: Int) = URI(server.urlFor(index)).rawPath

    private fun client() = RawHttpClient(server.localPort)

    @Test
    fun `url contains token, index and sanitised name`() {
        val url = server.urlFor(0)
        assertEquals("http://127.0.0.1:${server.localPort}/${server.token}/0/Holiday_photo.jpg", url)
        assertEquals(32, server.token.length)
        assertTrue(server.token.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun `tokens differ between sessions`() {
        val other = MediaServer(emptyList(), InetAddress.getLoopbackAddress())
        assertFalse(other.token == server.token)
    }

    @Test
    fun `advertised authority replaces bound address`() {
        val s = MediaServer(
            listOf(MediaItem("a.mp4", ByteArraySource(video, "video/mp4"))),
            InetAddress.getLoopbackAddress(), advertisedAuthority = "127.0.0.1:8099",
        ).start()
        s.use { assertTrue(it.urlFor(0).startsWith("http://127.0.0.1:8099/${it.token}/0/")) }
    }

    @Test
    fun `GET returns the whole photo`() = client().use { c ->
        val r = c.request("GET", path(0))
        assertEquals(200, r.status)
        assertEquals("image/jpeg", r.header("Content-Type"))
        assertEquals(photo.size.toString(), r.header("Content-Length"))
        assertEquals("bytes", r.header("Accept-Ranges"))
        assertArrayEquals(photo, r.body)
    }

    @Test
    fun `HEAD returns GET headers without body`() = client().use { c ->
        val head = c.request("HEAD", path(1))
        val get = c.request("GET", path(1), "Range" to "bytes=0-0")
        assertEquals(200, head.status)
        assertEquals("video/mp4", head.header("Content-Type"))
        assertEquals(video.size.toString(), head.header("Content-Length"))
        assertEquals(0, head.body.size)
        // The connection stayed usable after HEAD, so no body bytes were sent.
        assertEquals(206, get.status)
        assertEquals(video[0], get.body.single())
    }

    @Test
    fun `keep-alive serves several requests on one connection`() = client().use { c ->
        repeat(3) { assertEquals(200, c.request("GET", path(0)).status) }
        assertEquals("keep-alive", c.request("HEAD", path(0)).header("Connection"))
    }

    @Test
    fun `unknown paths and wrong token are 404 without file names`() = client().use { c ->
        val wrongToken = path(0).replace(server.token, "0".repeat(32))
        for (p in listOf("/", "/${server.token}", "/${server.token}/7/x.jpg", "/${server.token}/abc/x", wrongToken)) {
            val r = c.request("GET", p)
            assertEquals(404, r.status, p)
            assertFalse(String(r.body).contains("Holiday"), p)
        }
        assertEquals(404, c.request("HEAD", "/").status)
    }

    @Test
    fun `unknown length is streamed until close without ranges`() = client().use { c ->
        val r = c.request("GET", path(2), "Range" to "bytes=1-")
        assertEquals(200, r.status)
        assertNull(r.header("Accept-Ranges"))
        assertNull(r.header("Content-Length"))
        assertEquals("close", r.header("Connection"))
        assertArrayEquals(byteArrayOf(1, 2, 3), r.body)
    }

    @Test
    fun `range forms`() = client().use { c ->
        val closed = c.request("GET", path(1), "Range" to "bytes=100-199")
        assertEquals(206, closed.status)
        assertEquals("bytes 100-199/${video.size}", closed.header("Content-Range"))
        assertEquals("100", closed.header("Content-Length"))
        assertArrayEquals(video.copyOfRange(100, 200), closed.body)

        val open = c.request("GET", path(1), "Range" to "bytes=${video.size - 10}-")
        assertEquals(206, open.status)
        assertEquals("bytes ${video.size - 10}-${video.size - 1}/${video.size}", open.header("Content-Range"))
        assertArrayEquals(video.copyOfRange(video.size - 10, video.size), open.body)

        val suffix = c.request("GET", path(1), "Range" to "bytes=-500")
        assertEquals(206, suffix.status)
        assertEquals("bytes ${video.size - 500}-${video.size - 1}/${video.size}", suffix.header("Content-Range"))
        assertArrayEquals(video.copyOfRange(video.size - 500, video.size), suffix.body)

        val clamped = c.request("GET", path(0), "Range" to "bytes=9990-20000")
        assertEquals("bytes 9990-9999/10000", clamped.header("Content-Range"))
        assertEquals(10, clamped.body.size)

        val head = c.request("HEAD", path(1), "Range" to "bytes=0-9")
        assertEquals(206, head.status)
        assertEquals("10", head.header("Content-Length"))
        assertEquals("bytes", head.header("Accept-Ranges"))
    }

    @Test
    fun `unsatisfiable range is 416 with total length`() = client().use { c ->
        val r = c.request("GET", path(1), "Range" to "bytes=999999999-")
        assertEquals(416, r.status)
        assertEquals("bytes */10485760", r.header("Content-Range"))
        // Connection survives a 416.
        assertEquals(200, c.request("GET", path(0)).status)
    }

    @Test
    fun `multi-range falls back to full content`() = client().use { c ->
        val r = c.request("GET", path(0), "Range" to "bytes=0-1,5-6")
        assertEquals(200, r.status)
        assertArrayEquals(photo, r.body)
    }

    @Test
    fun `DLNA headers only on request`() = client().use { c ->
        val plain = c.request("HEAD", path(1))
        assertNull(plain.header("contentFeatures.dlna.org"))
        assertNull(plain.header("transferMode.dlna.org"))

        val video = c.request("HEAD", path(1), "getcontentFeatures.dlna.org" to "1")
        assertTrue(video.header("contentFeatures.dlna.org")!!.contains("DLNA.ORG_OP=01"))
        assertEquals("Streaming", video.header("transferMode.dlna.org"))

        val photo = c.request("HEAD", path(0), "transferMode.dlna.org" to "Interactive")
        assertEquals("Interactive", photo.header("transferMode.dlna.org"))
        assertNull(photo.header("contentFeatures.dlna.org"))
    }

    @Test
    fun `end of file first then from start`() {
        val size = 20 * 1024 * 1024
        val data = Random(3).nextBytes(size)
        MediaServer(
            listOf(MediaItem("movie.mp4", CountingSource(data, "video/mp4"))),
            InetAddress.getLoopbackAddress(),
        ).start().use { s ->
            val p = URI(s.urlFor(0)).rawPath
            RawHttpClient(s.localPort).use { c ->
                val tail = c.request("GET", p, "Range" to "bytes=${size - 1048576}-")
                assertEquals(206, tail.status)
                assertEquals("bytes ${size - 1048576}-${size - 1}/$size", tail.header("Content-Range"))
                assertArrayEquals(data.copyOfRange(size - 1048576, size), tail.body)

                val head = c.request("GET", p, "Range" to "bytes=0-65535")
                assertEquals(206, head.status)
                assertArrayEquals(data.copyOfRange(0, 65536), head.body)
            }
        }
    }

    @Test
    fun `client dropping a long response does not affect later requests`() {
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use { raw ->
            raw.getOutputStream().write("GET ${path(1)} HTTP/1.1\r\nRange: bytes=0-\r\n\r\n".toByteArray())
            raw.getInputStream().readNBytes(1000)
        }
        client().use { c -> assertEquals(206, c.request("GET", path(1), "Range" to "bytes=0-9").status) }
    }

    @Test
    fun `close stops listening and drops connections`() {
        val port = server.localPort
        val c = client()
        assertEquals(200, c.request("GET", path(0)).status)
        server.close()
        assertThrows<ConnectException> { Socket(InetAddress.getLoopbackAddress(), port).close() }
        assertThrows<Exception> { c.request("GET", path(0)) }
        c.close()
    }

    private class UnknownLengthSource(override val mime: String, private val bytes: ByteArray) : ByteSource {
        override val length: Long? = null
        override fun open(offset: Long): InputStream = bytes.inputStream().also { it.skip(offset) }
    }

    private class CountingSource(private val bytes: ByteArray, override val mime: String) : ByteSource {
        override val length: Long get() = bytes.size.toLong()
        override fun open(offset: Long): InputStream {
            require(offset in 0..bytes.size) { "offset $offset" }
            return bytes.inputStream(offset.toInt(), bytes.size - offset.toInt())
        }
    }
}
