package io.github.nezdelion.dlnatvcast.core.cast

import io.github.nezdelion.dlnatvcast.core.MediaItem
import io.github.nezdelion.dlnatvcast.core.http.ByteArraySource
import io.github.nezdelion.dlnatvcast.core.http.MediaServer
import io.github.nezdelion.dlnatvcast.core.session.FakeRenderer
import io.github.nezdelion.dlnatvcast.core.session.RendererClient
import io.github.nezdelion.dlnatvcast.core.session.TransportState
import io.github.nezdelion.dlnatvcast.core.upnp.RendererDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.net.URI

/** Review findings #1, #3 (destroy), #8 (wake ownership) and #15 (intake) on the extracted lifecycle. */
class CastCoordinatorTest {
    private val tv = RendererDevice("uuid:tv", "TV", "http://192.168.50.23:49494/description.xml", "http://192.168.50.23:49494/avt", null)

    private inner class Platform : CastPlatform<String> {
        var lan: InetAddress? = InetAddress.getLoopbackAddress()
        var remembered: RendererDevice? = tv
        val renderers = mutableListOf<FakeRenderer>()
        var nextRenderer: () -> FakeRenderer = { FakeRenderer(hasRenderingControl = false) }
        val servers = mutableListOf<MediaServer>()
        val inspected = mutableListOf<String>()
        val awake = mutableListOf<Boolean>()
        var finished = 0
        var rememberedDevices = mutableListOf<RendererDevice>()

        override fun lanAddress() = lan
        override suspend fun inspect(item: String): MediaItem? {
            inspected += item
            return if (item.startsWith("bad")) null else MediaItem("$item.jpg", ByteArraySource(item.toByteArray(), "image/jpeg"))
        }
        override fun startServer(items: List<MediaItem>, lan: InetAddress) =
            MediaServer(items, InetAddress.getLoopbackAddress()).start().also { servers += it }
        override fun rememberedName() = remembered?.friendlyName
        override suspend fun resolveRemembered(lan: InetAddress) = remembered
        override fun remember(device: RendererDevice) { rememberedDevices += device }
        override fun client(device: RendererDevice): RendererClient = nextRenderer().also { renderers += it }
        override fun keepAwake(on: Boolean) { awake += on }
        override fun finished() { finished++ }
    }

    private val platform = Platform()

    @AfterEach
    fun closeServers() = platform.servers.forEach { it.close() }

    private fun TestScope.coordinator() = CastCoordinator(backgroundScope, platform, cleanupScope = backgroundScope)

    private fun listening(server: MediaServer): Boolean = try {
        Socket(InetAddress.getLoopbackAddress(), server.localPort).close()
        true
    } catch (_: ConnectException) {
        false
    }

    private fun serves(server: MediaServer): Boolean = try {
        val url = URI(server.urlFor(0))
        (url.toURL().openConnection() as java.net.HttpURLConnection).run {
            connectTimeout = 1000; readTimeout = 1000
            responseCode.also { disconnect() } == 200
        }
    } catch (_: Exception) {
        false
    }

    private val CastCoordinator<String>.casting get() = state.value.phase as CastPhase.Casting

    // #1

    @Test
    fun `a delayed Stop from an earlier end does not touch a newer share`() = runTest {
        val gate = CompletableDeferred<Unit>()
        platform.nextRenderer = { FakeRenderer(hasRenderingControl = false).apply { hook = { if (it == "Stop") gate.await() } } }
        val c = coordinator()
        c.share(listOf("a"), 0); runCurrent()
        val first = c.casting.session
        val firstServer = platform.servers[0]

        c.end(); runCurrent()                       // Stop to the renderer is now stuck
        assertFalse(listening(firstServer), "old URLs must stop at once")

        platform.nextRenderer = { FakeRenderer(hasRenderingControl = false) }
        c.share(listOf("b"), 0); runCurrent()
        val second = c.casting.session
        val secondServer = platform.servers[1]
        assertTrue(second !== first)

        gate.complete(Unit); runCurrent()            // the old Stop finally returns

        assertSame(second, c.casting.session)
        assertFalse(second.state.value.closed)
        assertTrue(serves(secondServer), "new share must keep serving")
        assertFalse(listening(firstServer))
        assertEquals(0, platform.finished, "service must not be stopped")
        assertEquals(true, platform.awake.last())
        assertTrue(first.state.value.closed)
    }

    @Test
    fun `a new share replaces the queue without stopping the renderer`() = runTest {
        val c = coordinator()
        c.share(listOf("a"), 0); runCurrent()
        val first = c.casting.session
        c.share(listOf("b"), 0); runCurrent()
        assertTrue(first.state.value.closed)
        assertFalse(platform.renderers[0].commands.contains("Stop"))
        assertEquals(listOf("b.jpg"), c.casting.session.state.value.queue.map { it.title })
        assertFalse(listening(platform.servers[0]))
        assertTrue(serves(platform.servers[1]))
    }

    // #3

    @Test
    fun `destroy never waits for the renderer and closes the server at once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        platform.nextRenderer = { FakeRenderer(hasRenderingControl = false).apply { hook = { if (it == "Stop") gate.await() } } }
        val c = coordinator()
        c.share(listOf("a"), 0); runCurrent()
        c.destroy()                                   // plain call, nothing to run: must not block
        assertFalse(listening(platform.servers[0]))
        assertEquals(false, platform.awake.last())
        runCurrent()
        assertTrue(platform.renderers[0].commands.contains("Stop"), "best-effort Stop is sent in the cleanup scope")
        gate.complete(Unit)
    }

    // #8: wake ownership follows the session on every path.

    @Test
    fun `wake locks are held only while casting`() = runTest {
        val c = coordinator()
        c.share(listOf("a"), 0); runCurrent()
        assertEquals(true, platform.awake.last())
        c.share(listOf("b"), 0)
        assertEquals(false, platform.awake.last(), "released while the replacement is prepared")
        runCurrent()
        assertEquals(true, platform.awake.last())
        c.end(); runCurrent()
        assertEquals(false, platform.awake.last())

        platform.remembered = null
        c.share(listOf("c"), 0); runCurrent()
        assertTrue(c.state.value.phase is CastPhase.ChooseRenderer)
        assertEquals(false, platform.awake.last())
        c.select(tv); runCurrent()
        assertEquals(true, platform.awake.last())
        c.destroy()
        assertEquals(false, platform.awake.last())

        platform.lan = null
        c.share(listOf("d"), 0); runCurrent()
        assertEquals(false, platform.awake.last())
    }

    // #15: intake order, skipped count, no renderer contact without readable items.

    @Test
    fun `mixed share keeps order and counts skipped items`() = runTest {
        platform.remembered = null
        val c = coordinator()
        c.share(listOf("a", "bad1", "c"), unreadable = 1); runCurrent()
        assertEquals(CoordinatorState(CastPhase.ChooseRenderer(2), skipped = 2), c.state.value)
        c.select(tv); runCurrent()
        assertEquals(listOf("a.jpg", "c.jpg"), c.casting.session.state.value.queue.map { it.title })
        assertEquals(2, c.state.value.skipped)
        assertEquals(listOf(tv), platform.rememberedDevices)
        assertEquals("SetAVTransportURI ${c.casting.session.state.value.queue[0].url}", platform.renderers[0].commands.first())
    }

    @Test
    fun `nothing readable fails without contacting a renderer`() = runTest {
        val c = coordinator()
        c.share(listOf("bad1"), unreadable = 2); runCurrent()
        assertEquals(CastPhase.Failed(CastFailure.NothingReadable(3)), c.state.value.phase)
        assertTrue(platform.renderers.isEmpty())
        assertTrue(platform.servers.isEmpty())
        assertEquals(1, platform.finished)
        assertFalse(platform.awake.contains(true))
    }

    @Test
    fun `no network fails before reading items`() = runTest {
        platform.lan = null
        val c = coordinator()
        c.share(listOf("a"), 0); runCurrent()
        assertEquals(CastPhase.Failed(CastFailure.NoNetwork), c.state.value.phase)
        assertTrue(platform.inspected.isEmpty())
        assertTrue(platform.renderers.isEmpty())
    }

    @Test
    fun `destroy after a failure keeps the failure visible`() = runTest {
        platform.lan = null
        val c = coordinator()
        c.share(listOf("a"), 0); runCurrent()
        c.destroy()
        assertEquals(CastPhase.Failed(CastFailure.NoNetwork), c.state.value.phase)
    }

    @Test
    fun `end stops the renderer and finishes`() = runTest {
        val c = coordinator()
        c.share(listOf("a"), 0); runCurrent()
        c.end(); runCurrent()
        assertEquals("Stop", platform.renderers[0].commands.last())
        assertEquals(1, platform.finished)
        assertEquals(CastPhase.Idle, c.state.value.phase)
        assertEquals(TransportState.STOPPED, platform.renderers[0].state)
    }
}
