package io.github.nezdelion.dlnatvcast.core.cast

import io.github.nezdelion.dlnatvcast.core.MediaKind
import io.github.nezdelion.dlnatvcast.core.session.QueueEntry
import io.github.nezdelion.dlnatvcast.core.session.SessionState
import io.github.nezdelion.dlnatvcast.core.session.TransportState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Review #14: the notification reflects position and duration. */
class NotificationModelTest {
    private fun state(kind: MediaKind, position: Long?, duration: Long?) = SessionState(
        rendererName = "TV",
        queue = listOf(QueueEntry("Movie", "http://h/t/0/m", "video/mp4", kind, 1), QueueEntry("Next", "http://h/t/1/n", "video/mp4", kind, 1)),
        transport = TransportState.PLAYING,
        positionSec = position,
        durationSec = duration,
    )

    @Test
    fun `position and duration are shown and change every few seconds`() {
        val models = (0L..14L).map { NotificationModel.from(state(MediaKind.VIDEO, it, 1200)) }
        assertEquals(listOf(0L, 5L, 10L), models.distinct().map { it.positionSec })
        assertEquals(1200L, models.first().durationSec)
        assertEquals(true, models.first().hasNext)
        assertEquals(false, models.first().hasPrevious)
    }

    @Test
    fun `a seek shows up immediately`() {
        val before = NotificationModel.from(state(MediaKind.VIDEO, 12, 1200))
        val after = NotificationModel.from(state(MediaKind.VIDEO, 90, 1200))
        assertEquals(90L, after.positionSec)
        assertEquals(false, before == after)
    }

    @Test
    fun `images and unknown durations have no position`() {
        assertNull(NotificationModel.from(state(MediaKind.IMAGE, 3, 10)).positionSec)
        assertNull(NotificationModel.from(state(MediaKind.VIDEO, 3, null)).durationSec)
    }
}
