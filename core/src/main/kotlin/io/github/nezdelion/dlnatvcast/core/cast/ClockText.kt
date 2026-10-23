package io.github.nezdelion.dlnatvcast.core.cast

import java.util.Locale

/** Short clock for people (`1:30`, `1:02:03`), unlike the protocol format in `UpnpTime`. */
object ClockText {
    /** Hours are shown only when [total] (or [seconds] itself) reaches an hour, so both sides of `a / b` match. */
    fun format(seconds: Long, total: Long = seconds): String {
        val s = seconds.coerceAtLeast(0)
        return if (maxOf(s, total) >= 3600) {
            String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        } else {
            String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
        }
    }
}
