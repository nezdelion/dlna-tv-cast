package io.github.nezdelion.tvcast.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmokeTest {
    @Test
    fun `virtual time runs`() = runTest {
        delay(10_000)
        assertEquals(10_000, testScheduler.currentTime)
    }
}
