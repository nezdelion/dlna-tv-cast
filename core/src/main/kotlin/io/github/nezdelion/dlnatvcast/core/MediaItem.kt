package io.github.nezdelion.dlnatvcast.core

import io.github.nezdelion.dlnatvcast.core.http.ByteSource

enum class MediaKind {
    IMAGE, VIDEO, AUDIO;

    companion object {
        fun fromMime(mime: String): MediaKind? = when (mime.substringBefore('/').lowercase()) {
            "image" -> IMAGE
            "video" -> VIDEO
            "audio" -> AUDIO
            else -> null
        }
    }
}

/** One shared item: what the user sees as a title plus the bytes to serve. */
class MediaItem(val title: String, val source: ByteSource) {
    val mime: String get() = source.mime
    val kind: MediaKind get() = MediaKind.fromMime(source.mime) ?: MediaKind.VIDEO
}
