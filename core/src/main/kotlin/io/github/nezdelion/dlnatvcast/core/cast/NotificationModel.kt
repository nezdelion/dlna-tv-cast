package io.github.nezdelion.dlnatvcast.core.cast

import io.github.nezdelion.dlnatvcast.core.MediaKind
import io.github.nezdelion.dlnatvcast.core.session.SessionState
import io.github.nezdelion.dlnatvcast.core.session.TransportState

/**
 * What the media notification shows. Position is rounded down to [POSITION_STEP_SEC] so that
 * observing distinct models updates the notification every few seconds during playback and
 * right away after a seek, not on every poll.
 */
data class NotificationModel(
    val title: String,
    val rendererName: String,
    val index: Int,
    val count: Int,
    val transport: TransportState,
    val rendererLost: Boolean,
    val hasNext: Boolean,
    /** Null for images or when the duration is unknown. */
    val positionSec: Long?,
    val durationSec: Long?,
) {
    val playing: Boolean get() = transport == TransportState.PLAYING

    companion object {
        const val POSITION_STEP_SEC = 5L

        fun from(state: SessionState): NotificationModel {
            val timed = state.current.kind != MediaKind.IMAGE && state.durationSec != null
            return NotificationModel(
                title = state.current.title,
                rendererName = state.rendererName,
                index = state.index,
                count = state.queue.size,
                transport = state.transport,
                rendererLost = state.rendererLost,
                hasNext = state.hasNext,
                positionSec = if (timed) (state.positionSec ?: 0) / POSITION_STEP_SEC * POSITION_STEP_SEC else null,
                durationSec = if (timed) state.durationSec else null,
            )
        }
    }
}
