package io.github.nezdelion.dlnatvcast.core.cast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClockTextTest {
    @Test
    fun `short items drop the hours`() {
        assertEquals("0:05", ClockText.format(5))
        assertEquals("1:30", ClockText.format(90, 300))
        assertEquals("59:59", ClockText.format(3599))
    }

    @Test
    fun `long items show hours on both sides`() {
        assertEquals("0:01:30", ClockText.format(90, 3700))
        assertEquals("1:01:40", ClockText.format(3700))
    }
}
