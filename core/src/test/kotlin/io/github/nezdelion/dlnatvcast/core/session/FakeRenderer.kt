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

    private fun reach() {
        if (unreachable) throw IOException("connect timed out")
    }

    val commands: List<String> get() = calls.filterNot { it == "GetTransportInfo" || it == "GetPositionInfo" || it == "GetVolume" || it == "GetMute" }

    override suspend fun setUri(url: String, metadata: String) {
        reach(); calls += "SetAVTransportURI $url"
        faultOnSetUri?.let { throw it }
        uri = url; lastMetadata = metadata; state = TransportState.STOPPED
    }

    override suspend fun play() {
        reach(); calls += "Play"; state = TransportState.PLAYING; if (position == null) position = 0
    }

    override suspend fun pause() {
        reach(); calls += "Pause"; state = TransportState.PAUSED_PLAYBACK
    }

    override suspend fun stop() {
        reach(); calls += "Stop"; state = TransportState.STOPPED; position = null
    }

    override suspend fun seek(positionSec: Long) {
        reach(); calls += "Seek $positionSec"; position = positionSec
    }

    override suspend fun transportState(): TransportState {
        reach(); calls += "GetTransportInfo"; return state
    }

    override suspend fun positionInfo(): PositionInfo {
        reach(); calls += "GetPositionInfo"
        val playing = state == TransportState.PLAYING || state == TransportState.PAUSED_PLAYBACK
        return PositionInfo(if (playing) position else 0, if (playing) duration else null)
    }

    override suspend fun volume(): Int {
        reach(); calls += "GetVolume"; return vol
    }

    override suspend fun setVolume(volume: Int) {
        reach(); calls += "SetVolume $volume"; vol = volume
    }

    override suspend fun muted(): Boolean {
        reach(); calls += "GetMute"; return mute
    }

    override suspend fun setMuted(muted: Boolean) {
        reach(); calls += "SetMute $muted"; mute = muted
    }
}
