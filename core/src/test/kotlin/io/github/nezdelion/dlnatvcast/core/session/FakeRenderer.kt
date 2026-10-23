package io.github.nezdelion.dlnatvcast.core.session

import io.github.nezdelion.dlnatvcast.core.upnp.UpnpException
import java.io.IOException

/** Scriptable renderer: tests set [state]/[position]/[duration] and read [calls]. */
class FakeRenderer(override val hasRenderingControl: Boolean = true) : RendererClient {
    override val name = "Fake TV"
    val calls = mutableListOf<String>()
    var state = TransportState.NO_MEDIA_PRESENT
    var position: Long? = null
    var duration: Long? = null
    var vol = 30
    var mute = false
    var unreachable = false
    var faultOnSetUri: UpnpException? = null
    var lastMetadata: String? = null
    var uri: String? = null

    /** Runs after a call is recorded and before it takes effect; may suspend, delay or throw. */
    var hook: suspend (action: String) -> Unit = {}
    var clock: () -> Long = { 0 }
    val timeline = mutableListOf<Pair<Long, String>>()

    private fun reach() {
        if (unreachable) throw IOException("connect timed out")
    }

    private suspend fun enter(action: String) {
        reach()
        calls += action
        timeline += clock() to action
        hook(action)
    }

    val commands: List<String> get() = calls.filterNot { it == "GetTransportInfo" || it == "GetPositionInfo" || it == "GetVolume" || it == "GetMute" }

    override suspend fun setUri(url: String, metadata: String) {
        enter("SetAVTransportURI $url")
        faultOnSetUri?.let { throw it }
        uri = url; lastMetadata = metadata; state = TransportState.STOPPED
    }

    override suspend fun play() {
        enter("Play"); state = TransportState.PLAYING; if (position == null) position = 0
    }

    override suspend fun pause() {
        enter("Pause"); state = TransportState.PAUSED_PLAYBACK
    }

    override suspend fun stop() {
        enter("Stop"); state = TransportState.STOPPED; position = null
    }

    override suspend fun seek(positionSec: Long) {
        enter("Seek $positionSec"); position = positionSec
    }

    override suspend fun transportState(): TransportState {
        enter("GetTransportInfo"); return state
    }

    override suspend fun positionInfo(): PositionInfo {
        enter("GetPositionInfo")
        val playing = state == TransportState.PLAYING || state == TransportState.PAUSED_PLAYBACK
        return PositionInfo(if (playing) position else 0, if (playing) duration else null)
    }

    override suspend fun volume(): Int {
        enter("GetVolume"); return vol
    }

    override suspend fun setVolume(volume: Int) {
        enter("SetVolume $volume"); vol = volume
    }

    override suspend fun muted(): Boolean {
        enter("GetMute"); return mute
    }

    override suspend fun setMuted(muted: Boolean) {
        enter("SetMute $muted"); mute = muted
    }
}
