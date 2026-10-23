package io.github.nezdelion.dlnatvcast.core.session

import io.github.nezdelion.dlnatvcast.core.MediaKind
import io.github.nezdelion.dlnatvcast.core.upnp.UpnpException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastSessionTest {
    private val renderer = FakeRenderer()

    private fun entry(i: Int, kind: MediaKind = MediaKind.VIDEO) = QueueEntry(
        title = "Item & $i",
        url = "http://10.0.0.2:4000/tok/$i/item$i",
        mime = when (kind) {
            MediaKind.IMAGE -> "image/jpeg"
            MediaKind.VIDEO -> "video/mp4"
            MediaKind.AUDIO -> "audio/mpeg"
        },
        kind = kind,
        size = 1000L + i,
    )

    private fun TestScope.session(vararg kinds: MediaKind): CastSession =
        CastSession(
            renderer,
            kinds.mapIndexed { i, k -> entry(i, k) },
            backgroundScope,
            now = { testScheduler.currentTime },
        ).also { it.start(); runCurrent() }

    /** Lets the renderer "play" for [seconds], advancing its position, with polls in between. */
    private fun TestScope.play(seconds: Int) = repeat(seconds) {
        if (renderer.state == TransportState.PLAYING) renderer.position = (renderer.position ?: 0) + 1
        advanceTimeBy(1_000)
        runCurrent()
    }

    // 4.1 commands

    @Test
    fun `start sends SetAVTransportURI with DIDL then Play`() = runTest {
        val s = session(MediaKind.VIDEO)
        assertEquals(listOf("SetAVTransportURI ${entry(0).url}", "Play"), renderer.commands)
        assertTrue(renderer.lastMetadata!!.contains("<dc:title>Item &amp; 0</dc:title>"))
        assertTrue(renderer.lastMetadata!!.contains("object.item.videoItem"))
        assertTrue(renderer.lastMetadata!!.contains("size=\"1000\""))
        renderer.duration = 60
        play(1)
        assertEquals(TransportState.PLAYING, s.state.value.transport)
        assertEquals(60L, s.state.value.durationSec)
        assertEquals(30, s.state.value.volume)
        assertTrue(s.state.value.canSeek)
    }

    @Test
    fun `pause resume stop seek and volume`() = runTest {
        val s = session(MediaKind.VIDEO)
        renderer.duration = 600
        play(2)
        s.togglePlayPause()
        assertEquals(TransportState.PAUSED_PLAYBACK, s.state.value.transport)
        s.togglePlayPause()
        assertEquals(TransportState.PLAYING, s.state.value.transport)
        s.seek(90)
        s.stop()
        assertEquals(TransportState.STOPPED, s.state.value.transport)
        s.resume()
        s.changeVolume(+5)
        s.toggleMute()
        assertEquals(
            listOf("Pause", "Play", "Seek 90", "Stop", "Play", "SetVolume 35", "SetMute true"),
            renderer.commands.drop(2),
        )
        assertEquals(35, s.state.value.volume)
        assertEquals(true, s.state.value.muted)
    }

    @Test
    fun `next and previous stay within the queue`() = runTest {
        val s = session(MediaKind.IMAGE, MediaKind.IMAGE, MediaKind.IMAGE, MediaKind.IMAGE, MediaKind.IMAGE)
        assertFalse(s.state.value.hasPrevious)
        s.previous()
        assertEquals(0, s.state.value.index)
        s.next()
        s.next()
        assertEquals(2, s.state.value.index)
        assertEquals("SetAVTransportURI ${entry(2).url}", renderer.commands[renderer.commands.size - 2])
        assertTrue(renderer.lastMetadata!!.contains("object.item.imageItem.photo"))
        assertFalse(s.state.value.canSeek)
        s.playItem(4)
        s.next()
        assertEquals(4, s.state.value.index)
        assertFalse(s.state.value.hasNext)
        s.previous()
        assertEquals(3, s.state.value.index)
    }

    @Test
    fun `UPnP fault is shown, item kept and not retried`() = runTest {
        renderer.faultOnSetUri = UpnpException(714, "Illegal MIME-type")
        val s = session(MediaKind.VIDEO, MediaKind.VIDEO)
        play(5)
        assertEquals("UPnP error 714: Illegal MIME-type", s.state.value.error)
        assertEquals(0, s.state.value.index)
        assertEquals(1, renderer.calls.count { it.startsWith("SetAVTransportURI") })
        assertFalse(renderer.calls.contains("Play"))
    }

    @Test
    fun `renderer without RenderingControl has no volume`() = runTest {
        val r = FakeRenderer(hasRenderingControl = false)
        val s = CastSession(r, listOf(entry(0)), backgroundScope, now = { testScheduler.currentTime })
        s.start(); runCurrent()
        advanceTimeBy(6_000); runCurrent()
        assertFalse(s.state.value.hasVolume)
        assertNull(s.state.value.volume)
        assertFalse(r.calls.contains("GetVolume"))
    }

    // 4.2 polling and state machine

    @Test
    fun `natural end of a video starts the next one`() = runTest {
        val s = session(MediaKind.VIDEO, MediaKind.VIDEO)
        renderer.duration = 10
        play(9)
        assertEquals(9L, s.state.value.positionSec)
        renderer.state = TransportState.STOPPED // file ended on the renderer
        play(1)
        assertEquals(1, s.state.value.index)
        assertEquals("SetAVTransportURI ${entry(1).url}", renderer.commands.dropLast(1).last())
    }

    @Test
    fun `last item ending keeps the session open and stopped`() = runTest {
        val s = session(MediaKind.AUDIO)
        renderer.duration = 5
        play(5)
        renderer.state = TransportState.STOPPED
        play(3)
        assertEquals(TransportState.STOPPED, s.state.value.transport)
        assertFalse(s.state.value.closed)
        assertEquals(1, renderer.calls.count { it.startsWith("SetAVTransportURI") })
    }

    @Test
    fun `stop on the renderer far from the end is a user stop`() = runTest {
        val s = session(MediaKind.VIDEO, MediaKind.VIDEO)
        renderer.duration = 120
        play(10)
        renderer.state = TransportState.STOPPED // "q" in mpv
        play(5)
        assertEquals(0, s.state.value.index)
        assertEquals(TransportState.STOPPED, s.state.value.transport)
        assertEquals(1, renderer.calls.count { it.startsWith("SetAVTransportURI") })
    }

    @Test
    fun `stop from the app near the end does not advance`() = runTest {
        val s = session(MediaKind.VIDEO, MediaKind.VIDEO)
        renderer.duration = 10
        play(9)
        s.stop()
        play(3)
        assertEquals(0, s.state.value.index)
    }

    @Test
    fun `unknown duration advances unless the app stopped`() = runTest {
        val s = session(MediaKind.AUDIO, MediaKind.AUDIO, MediaKind.AUDIO)
        renderer.duration = null
        play(4)
        renderer.state = TransportState.STOPPED
        play(1)
        assertEquals(1, s.state.value.index)
        play(2)
        s.stop()
        play(2)
        assertEquals(1, s.state.value.index)
    }

    @Test
    fun `images never auto-advance`() = runTest {
        val s = session(MediaKind.IMAGE, MediaKind.IMAGE)
        play(3)
        renderer.state = TransportState.STOPPED
        play(3)
        assertEquals(0, s.state.value.index)
        assertEquals(TransportState.STOPPED, s.state.value.transport)
    }

    @Test
    fun `transitioning before playing does not count as an end`() = runTest {
        val s = session(MediaKind.VIDEO, MediaKind.VIDEO)
        renderer.state = TransportState.TRANSITIONING
        play(2)
        renderer.state = TransportState.STOPPED // load failed on the renderer
        play(2)
        assertEquals(0, s.state.value.index)
    }

    @Test
    fun `pause on the renderer shows within one poll`() = runTest {
        val s = session(MediaKind.VIDEO)
        renderer.duration = 100
        play(3)
        renderer.state = TransportState.PAUSED_PLAYBACK
        advanceTimeBy(1_001); runCurrent()
        assertEquals(TransportState.PAUSED_PLAYBACK, s.state.value.transport)
    }

    @Test
    fun `poll interval is 1 s in foreground and 5 s in background`() = runTest {
        val s = session(MediaKind.VIDEO)
        fun polls() = renderer.calls.count { it == "GetTransportInfo" }
        val a = polls()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(10, polls() - a)
        s.setForeground(false)
        runCurrent()
        val b = polls()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(4, polls() - b)
        s.setForeground(true)
        runCurrent()
        assertEquals(b + 5, polls()) // switching back polls immediately
    }

    @Test
    fun `background end is detected between slow polls`() = runTest {
        val s = session(MediaKind.VIDEO, MediaKind.VIDEO)
        s.setForeground(false); runCurrent()
        renderer.duration = 30
        renderer.position = 24
        advanceTimeBy(5_000); runCurrent() // poll sees 24 s
        renderer.state = TransportState.STOPPED // ended at 30 s, between polls
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, s.state.value.index)
    }

    @Test
    fun `renderer lost after 15 s of failures, retry recovers`() = runTest {
        val s = session(MediaKind.VIDEO)
        play(2)
        renderer.unreachable = true
        advanceTimeBy(14_000); runCurrent()
        assertFalse(s.state.value.rendererLost)
        advanceTimeBy(2_000); runCurrent()
        assertTrue(s.state.value.rendererLost)
        assertFalse(s.state.value.closed)
        renderer.unreachable = false
        s.retry(); runCurrent()
        assertFalse(s.state.value.rendererLost)
        assertEquals(TransportState.PLAYING, s.state.value.transport)
    }

    @Test
    fun `a single failed poll is not a lost renderer`() = runTest {
        val s = session(MediaKind.VIDEO)
        renderer.unreachable = true
        advanceTimeBy(3_000); runCurrent()
        renderer.unreachable = false
        advanceTimeBy(14_000); runCurrent()
        renderer.unreachable = true
        advanceTimeBy(5_000); runCurrent()
        assertFalse(s.state.value.rendererLost)
    }

    @Test
    fun `close stops the renderer and polling`() = runTest {
        val s = session(MediaKind.VIDEO)
        play(2)
        s.close()
        assertTrue(s.state.value.closed)
        assertEquals("Stop", renderer.commands.last())
        val n = renderer.calls.size
        advanceTimeBy(10_000); runCurrent()
        assertEquals(n, renderer.calls.size)
    }
}
