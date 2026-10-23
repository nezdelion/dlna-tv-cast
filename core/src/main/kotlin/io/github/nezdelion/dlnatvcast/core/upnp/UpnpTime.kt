package io.github.nezdelion.dlnatvcast.core.upnp

import java.util.Locale

/** UPnP AV time strings (`H+:MM:SS[.F+]`). */
object UpnpTime {
    fun format(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    /** Returns whole seconds, or null for `NOT_IMPLEMENTED`, empty or malformed values. */
    fun parse(text: String?): Long? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = value.substringBefore('.').split(':')
        if (parts.size != 3) return null
        val (h, m, s) = parts.map { it.toLongOrNull() ?: return null }
        if (h < 0 || m !in 0..59 || s !in 0..59) return null
        return h * 3600 + m * 60 + s
    }
}
