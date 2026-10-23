package io.github.nezdelion.dlnatvcast.core.session

import io.github.nezdelion.dlnatvcast.core.MediaItem
import io.github.nezdelion.dlnatvcast.core.http.FileByteSource
import io.github.nezdelion.dlnatvcast.core.http.MediaServer
import io.github.nezdelion.dlnatvcast.core.upnp.DeviceDescription
import io.github.nezdelion.dlnatvcast.core.upnp.UpnpRendererClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files

/**
 * Drives a real renderer with videos served from this machine. Shows video on the TV, so each
 * case is short and every path ends with Stop.
 */
@Tag("renderer")
@EnabledIfSystemProperty(named = "tvcast.renderer", matches = "http.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RealRendererTest {
    private val location = System.getProperty("tvcast.renderer")
    private lateinit var dir: File
    private lateinit var client: UpnpRendererClient
    private lateinit var localAddress: InetAddress

    @BeforeAll
    fun setUp() {
        dir = Files.createTempDirectory("tvcast-it").toFile()
        // Index at the end of the file (no faststart) so the renderer has to seek to the tail first.
        video("long.mp4", "testsrc=size=640x360:rate=25", 20)
        video("first.mp4", "smptebars=size=640x360:rate=25", 4)
        video("second.mp4", "testsrc2=size=640x360:rate=25", 4)
        client = UpnpRendererClient(DeviceDescription.fetch(location))
        val host = URI(location).host
        localAddress = DatagramSocket().use {
            it.connect(InetSocketAddress(host, 9))
            it.localAddress
        }
        log("renderer ${client.device.friendlyName} at $host, serving from ${localAddress.hostAddress}")
    }

    @AfterAll
    fun tearDown() {
        runBlocking { runCatching { client.stop() } }
        dir.deleteRecursively()
    }

    private fun video(name: String, source: String, seconds: Int) {
        val out = File(dir, name)
        val process = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "$source:duration=$seconds",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=$seconds",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", out.path,
        ).inheritIO().start()
        check(process.waitFor() == 0 && out.length() > 0) { "ffmpeg failed for $name" }
    }

    private fun log(message: String) = println("[renderer-it] $message")

    private fun <T> withSession(vararg names: String, block: suspend (CastSession) -> T): T {
        val items = names.map { MediaItem(it, FileByteSource(File(dir, it), "video/mp4")) }
        val server = MediaServer(items, localAddress).start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queue = items.mapIndexed { i, item ->
            QueueEntry(item.title, server.urlFor(i), item.mime, item.kind, item.source.length)
        }
        val session = CastSession(client, queue, scope)
        return try {
            runBlocking {
                session.start()
                block(session)
            }
        } finally {
            runBlocking { session.close(stopRenderer = true) }
            scope.cancel()
            server.close()
        }
    }

    private suspend fun CastSession.await(what: String, timeoutMs: Long = 15_000, predicate: (SessionState) -> Boolean): SessionState =
        try {
            withTimeout(timeoutMs) { state.first(predicate) }.also { log("$what: $it".take(200)) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what; last state ${state.value}", e)
        }

    @Test
    @Order(1)
    fun `play seek pause resume stop`() = withSession("long.mp4") { s ->
        s.await("playing") { it.transport == TransportState.PLAYING && it.durationSec != null }
        assertEquals(20L, s.state.value.durationSec)

        s.seek(12)
        s.await("position after seek") { it.transport == TransportState.PLAYING && (it.positionSec ?: 0) >= 12 }

        s.pause()
        delay(1_500)
        assertEquals(TransportState.PAUSED_PLAYBACK, client.transportState())
        s.await("paused in state", 2_000) { it.transport == TransportState.PAUSED_PLAYBACK }

        s.resume()
        delay(1_500)
        assertEquals(TransportState.PLAYING, client.transportState())

        s.stop()
        delay(1_000)
        assertEquals(TransportState.STOPPED, client.transportState())
        delay(2_000)
        // A stop from the app must not be mistaken for the end of the item.
        assertEquals(0, s.state.value.index)
        assertEquals(TransportState.STOPPED, s.state.value.transport)
    }

    @Test
    @Order(2)
    fun `two videos advance automatically`() = withSession("first.mp4", "second.mp4") { s ->
        s.await("first playing") { it.index == 0 && it.transport == TransportState.PLAYING }
        s.await("second playing", 20_000) { it.index == 1 && it.transport == TransportState.PLAYING }
        assertTrue(s.state.value.current.url.contains("/1/second.mp4"))
    }

    @Test
    @Order(3)
    fun `renderer is left stopped`() = runBlocking {
        val state = client.transportState()
        log("final renderer state $state")
        assertTrue(state == TransportState.STOPPED || state == TransportState.NO_MEDIA_PRESENT)
    }
}
