package io.github.nezdelion.dlnatvcast.core.cast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ShareIntakeTest {
    @Test
    fun `readable items keep their order, the rest are counted`() {
        val (readable, skipped) = ShareIntake.splitReadable(listOf("a", "x", "b", "y", "c")) { it in setOf("a", "b", "c") }
        assertEquals(listOf("a", "b", "c"), readable)
        assertEquals(2, skipped)
    }
}
