package io.github.nezdelion.dlnatvcast.core.session

enum class TransportState {
    PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT, UNKNOWN;

    companion object {
        fun parse(value: String?): TransportState =
            entries.firstOrNull { it.name == value?.trim() } ?: UNKNOWN
    }
}

data class PositionInfo(val positionSec: Long?, val durationSec: Long?)

/** Commands the session needs from a renderer. Implementations throw IOException on failure. */
interface RendererClient {
    val name: String
    val hasRenderingControl: Boolean

    suspend fun setUri(url: String, metadata: String)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seek(positionSec: Long)
    suspend fun transportState(): TransportState
    suspend fun positionInfo(): PositionInfo
    suspend fun volume(): Int
    suspend fun setVolume(volume: Int)
    suspend fun muted(): Boolean
    suspend fun setMuted(muted: Boolean)
}
