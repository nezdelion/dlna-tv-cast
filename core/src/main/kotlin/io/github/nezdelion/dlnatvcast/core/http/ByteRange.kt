package io.github.nezdelion.dlnatvcast.core.http

sealed interface ByteRange {
    /** Serve the whole entity with 200. */
    data object Full : ByteRange

    /** Serve bytes [start]..[end] inclusive with 206. */
    data class Partial(val start: Long, val end: Long) : ByteRange {
        val count: Long get() = end - start + 1
    }

    /** Answer 416. */
    data object Unsatisfiable : ByteRange

    companion object {
        /**
         * Parses a single `Range: bytes=...` header against an entity of [length] bytes.
         * Malformed headers, multi-range requests and unknown lengths fall back to [Full],
         * as RFC 9110 allows a server to ignore Range.
         */
        fun parse(header: String?, length: Long?): ByteRange {
            if (header == null || length == null) return Full
            val value = header.trim()
            if (!value.startsWith("bytes=", ignoreCase = true)) return Full
            val spec = value.substring(6).trim()
            if (spec.contains(',')) return Full
            val dash = spec.indexOf('-')
            if (dash < 0) return Full
            val first = spec.substring(0, dash).trim()
            val last = spec.substring(dash + 1).trim()
            if (first.isEmpty()) {
                val suffix = last.toLongOrNull() ?: return Full
                if (suffix <= 0 || length == 0L) return Unsatisfiable
                return Partial(maxOf(0, length - suffix), length - 1)
            }
            val start = first.toLongOrNull() ?: return Full
            if (start < 0) return Full
            val end = if (last.isEmpty()) Long.MAX_VALUE else last.toLongOrNull() ?: return Full
            if (end < start) return Full
            if (start >= length) return Unsatisfiable
            return Partial(start, minOf(end, length - 1))
        }
    }
}
