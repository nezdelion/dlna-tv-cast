package io.github.nezdelion.dlnatvcast.core.upnp

import io.github.nezdelion.dlnatvcast.core.session.PositionInfo
import io.github.nezdelion.dlnatvcast.core.session.RendererClient
import io.github.nezdelion.dlnatvcast.core.session.TransportState

class UpnpRendererClient(
    val device: RendererDevice,
    private val soap: SoapClient = SoapClient(),
) : RendererClient {
    override val name: String get() = device.friendlyName
    override val hasRenderingControl: Boolean get() = device.renderingControlUrl != null

    private suspend fun avt(action: String, vararg args: Pair<String, String>): Map<String, String> =
        soap.call(device.avTransportControlUrl, device.avTransportType, action, listOf(INSTANCE) + args)

    private suspend fun rcs(action: String, vararg args: Pair<String, String>): Map<String, String> {
        val url = device.renderingControlUrl ?: throw UpnpException(null, "Renderer has no volume control")
        return soap.call(url, device.renderingControlType, action, listOf(INSTANCE, CHANNEL) + args)
    }

    override suspend fun setUri(url: String, metadata: String) {
        avt("SetAVTransportURI", "CurrentURI" to url, "CurrentURIMetaData" to metadata)
    }

    override suspend fun play() {
        avt("Play", "Speed" to "1")
    }

    override suspend fun pause() {
        avt("Pause")
    }

    override suspend fun stop() {
        avt("Stop")
    }

    override suspend fun seek(positionSec: Long) {
        avt("Seek", "Unit" to "REL_TIME", "Target" to UpnpTime.format(positionSec))
    }

    override suspend fun transportState(): TransportState =
        TransportState.parse(avt("GetTransportInfo")["CurrentTransportState"])

    override suspend fun positionInfo(): PositionInfo {
        val out = avt("GetPositionInfo")
        return PositionInfo(
            positionSec = UpnpTime.parse(out["RelTime"]),
            // Renderers report 0:00:00 when they do not know the duration.
            durationSec = UpnpTime.parse(out["TrackDuration"])?.takeIf { it > 0 },
        )
    }

    override suspend fun volume(): Int = rcs("GetVolume")["CurrentVolume"]?.trim()?.toIntOrNull() ?: 0

    override suspend fun setVolume(volume: Int) {
        rcs("SetVolume", "DesiredVolume" to volume.coerceIn(0, 100).toString())
    }

    override suspend fun muted(): Boolean =
        rcs("GetMute")["CurrentMute"]?.trim()?.lowercase() in setOf("1", "true", "yes")

    override suspend fun setMuted(muted: Boolean) {
        rcs("SetMute", "DesiredMute" to if (muted) "1" else "0")
    }

    private companion object {
        val INSTANCE = "InstanceID" to "0"
        val CHANNEL = "Channel" to "Master"
    }
}
