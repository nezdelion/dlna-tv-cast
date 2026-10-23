package io.github.nezdelion.tvcast.core.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ByteRangeTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "bytes=0-99      | 0   | 99",
            "bytes=100-      | 100 | 999",
            "bytes=-100      | 900 | 999",
            "bytes=-5000     | 0   | 999",
            "bytes=990-5000  | 990 | 999",
            "BYTES=1-1       | 1   | 1",
        ],
    )
    fun partial(header: String, start: Long, end: Long) {
        assertEquals(ByteRange.Partial(start, end), ByteRange.parse(header, 1000))
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = ["bytes=1000-", "bytes=5000-6000", "bytes=-0"])
    fun unsatisfiable(header: String) {
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse(header, 1000))
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = ["items=0-1", "bytes=0-1,4-5", "bytes=5-1", "bytes=abc", "bytes=x-"])
    fun ignored(header: String) {
        assertEquals(ByteRange.Full, ByteRange.parse(header, 1000))
    }

    @org.junit.jupiter.api.Test
    fun `unknown length ignores ranges`() {
        assertEquals(ByteRange.Full, ByteRange.parse("bytes=0-1", null))
    }
}
