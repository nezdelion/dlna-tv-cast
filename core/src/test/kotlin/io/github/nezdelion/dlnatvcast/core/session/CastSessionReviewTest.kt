package io.github.nezdelion.dlnatvcast.core.session

import io.github.nezdelion.dlnatvcast.core.MediaKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/** Regression tests for review findings #2, #4, #5 and #6. */
class CastSessionReviewTest {
    private val renderer = FakeRenderer()

    private fun entry(i: Int, kind: MediaKind = MediaKind.VIDEO) =
        QueueEntry("Item $i", "http://10.0.0.2:4000/tok/$i/item$i", "video/mp4", kind, 1000L)

    private fun TestScope.session(vararg kinds: MediaKind): CastSession {
        renderer.clock = { testScheduler.currentTime }
        return CastSession(renderer, kinds.mapIndexed { i, k -> entry(i, k) }, backgroundScope, now = { testScheduler.currentTime })
            .also { it.start(); runCurrent() }
    }

    private fun TestScope.play(seconds: Int) = repeat(seconds) {
        if (renderer.state == TransportState.PLAYING) renderer.position = (renderer.position ?: 0) + 1
        advanceTimeBy(1_000)
        runCurrent()
    }

    // #2

    @Test
    fun `close during a suspended SetAVTransportURI never sends Play`() = runTest {
        val gate = CompletableDeferred<Unit>()
        renderer.hook = { if (it.startsWith("SetAVTransportURI")) gate.await() }
        val s = session(MediaKind.VIDEO, MediaKind.VIDEO)
        assertEquals(listOf("SetAVTransportURI ${entry(0).url}"), renderer.commands)
        val closing = launch { s.close() }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        closing.join()
        assertTrue(s.state.value.closed)
        assertEquals(listOf("SetAVTransportURI ${entry(0).url}", "Stop"), renderer.commands)
    }

    @Test
    fun `pending navigation is dropped by close`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val s = session(MediaKind.VIDEO, MediaKind.VIDEO)
        renderer.hook = { if (it.startsWith("SetAVTransportURI")) gate.await() }
        val nav = launch { s.next() }
        runCurrent()
        val closing = launch { s.close() }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        closing.join(); nav.join()
        assertEquals("Stop", renderer.commands.last())
        assertFalse(renderer.commands.contains("Play") && renderer.commands.lastIndexOf("Play") > renderer.commands.indexOf("SetAVTransportURI ${entry(1).url}"))
    }

    @Test
    fun `commands after close do nothing`() = runTest {
        val s = session(MediaKind.VIDEO, MediaKind.VIDEO)
        s.close()
        val before = renderer.calls.size
        s.next(); s.pause(); s.resume(); s.seek(10); s.setVolume(10); s.toggleMute(); s.playItem(1); s.stop()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(before, renderer.calls.size, renderer.calls.drop(before).toString())
    }

    // #4

    @Test
    fun `slow RenderingControl does not delay transport polling`() = runTest {
        renderer.hook = { if (it == "GetVolume" || it == "GetMute") delay(5_000) }
        val s = session(MediaKind.VIDEO)
        renderer.duration = 600
        advanceTimeBy(4_500); runCurrent()
        renderer.state = TransportState.PAUSED_PLAYBACK
        advanceTimeBy(1_600); runCurrent()
        assertEquals(TransportState.PAUSED_PLAYBACK, s.state.value.transport)
        val polls = renderer.timeline.filter { it.second == "GetTransportInfo" }.map { it.first }
        val gaps = polls.zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { it <= 1_000 }, gaps.toString())
    }

    // #5

    private suspend fun hangThenFail() {
        delay(5_000)
        throw IOException("read timed out")
    }

    @Test
    fun `renderer lost within 15 s although each poll waits for a timeout`() = runTest {
        val s = session(MediaKind.VIDEO)
        play(3)
        var down = false
        renderer.hook = { if (down && (it == "GetTransportInfo" || it == "GetPositionInfo")) hangThenFail() }
        val lastAnswer = renderer.timeline.last { it.second == "GetTransportInfo" }.first
        down = true
        advanceTimeBy(lastAnswer + 14_000 - testScheduler.currentTime); runCurrent()
        assertFalse(s.state.value.rendererLost)
        advanceTimeBy(1_500); runCurrent()
        assertTrue(s.state.value.rendererLost, "not lost ${testScheduler.currentTime - lastAnswer} ms after the last answer")
    }

    @Test
    fun `renderer lost within 15 s with the screen off`() = runTest {
        val s = session(MediaKind.VIDEO)
        s.setForeground(false)
        play(6)
        var down = false
        renderer.hook = { if (down && (it == "GetTransportInfo" || it == "GetPositionInfo")) hangThenFail() }
        val lastAnswer = renderer.timeline.last { it.second == "GetTransportInfo" }.first
        down = true
        advanceTimeBy(lastAnswer + 15_500 - testScheduler.currentTime); runCurrent()
        assertTrue(s.state.value.rendererLost)
        down = false
        advanceTimeBy(6_000); runCurrent()
        assertFalse(s.state.value.rendererLost)
    }

    // #6

    @Test
    fun `stop far from the end during an outage does not advance`() = runTest {
        val s = session(MediaKind.VIDEO, MediaKind.VIDEO)
        renderer.duration = 30
        play(5)
        renderer.unreachable = true
        advanceTimeBy(30_000); runCurrent()
        assertTrue(s.state.value.rendererLost)
        renderer.state = TransportState.STOPPED
        renderer.unreachable = false
        s.retry()
        play(3)
        assertEquals(0, s.state.value.index)
        assertEquals(TransportState.STOPPED, s.state.value.transport)
    }

    @Test
    fun `unknown duration after an outage does not advance`() = runTest {
        val s = session(MediaKind.AUDIO, MediaKind.AUDIO)
        renderer.duration = null
        play(5)
        renderer.unreachable = true
        advanceTimeBy(20_000); runCurrent()
        renderer.state = TransportState.NO_MEDIA_PRESENT
        renderer.unreachable = false
        play(3)
        assertEquals(0, s.state.value.index)
    }

    @Test
    fun `natural end right before a short blip still advances when seen near the end`() = runTest {
        val s = session(MediaKind.VIDEO, MediaKind.VIDEO)
        renderer.duration = 10
        play(9)
        renderer.unreachable = true
        play(2)
        renderer.state = TransportState.STOPPED
        renderer.unreachable = false
        play(2)
        assertEquals(1, s.state.value.index)
    }
}
