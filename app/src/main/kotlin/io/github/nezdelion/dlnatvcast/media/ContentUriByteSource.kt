package io.github.nezdelion.dlnatvcast.media

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import io.github.nezdelion.dlnatvcast.core.MediaItem
import io.github.nezdelion.dlnatvcast.core.MediaKind
import io.github.nezdelion.dlnatvcast.core.http.ByteSource
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Serves a shared `content://` URI with random access: the file descriptor's channel is
 * positioned at the requested offset. Providers that hand out pipes are not seekable; for them
 * the offset is skipped by reading, which is correct but slower.
 */
class ContentUriByteSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val mime: String,
    override val length: Long?,
) : ByteSource {

    override fun open(offset: Long): InputStream {
        val afd = resolver.openAssetFileDescriptor(uri, "r") ?: throw IOException("Cannot open $uri")
        val input = FileInputStream(afd.fileDescriptor)
        val stream = object : FilterInputStream(input) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    afd.close()
                }
            }
        }
        try {
            val start = afd.startOffset + offset
            try {
                input.channel.position(start)
            } catch (_: IOException) {
                skipFully(input, start)
            }
            return stream
        } catch (e: IOException) {
            stream.close()
            throw e
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("Stream ended before offset $count")
            remaining -= read
        }
    }

    companion object {
        /**
         * Reads name, size and type of a shared URI and checks that it can be opened.
         * Returns null for items that cannot be read or are not image/video/audio.
         */
        fun inspect(resolver: ContentResolver, uri: Uri, typeHint: String?): MediaItem? {
            var name: String? = null
            var size: Long? = null
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val s = c.getColumnIndex(OpenableColumns.SIZE)
                            if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                            if (s >= 0 && !c.isNull(s)) size = c.getLong(s).takeIf { it > 0 }
                        }
                    }
            } catch (_: Exception) {
                // Some providers do not support queries; the descriptor below still decides.
            }
            val descriptorLength = try {
                resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: return null
            } catch (_: Exception) {
                return null
            }
            if (size == null && descriptorLength != AssetFileDescriptor.UNKNOWN_LENGTH) size = descriptorLength
            val title = name ?: uri.lastPathSegment ?: "media"
            val mime = listOfNotNull(
                runCatching { resolver.getType(uri) }.getOrNull(),
                typeHint,
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(title.substringAfterLast('.', "").lowercase()),
            ).firstOrNull { MediaKind.fromMime(it) != null && !it.endsWith("/*") } ?: return null
            return MediaItem(title, ContentUriByteSource(resolver, uri, mime, size))
        }
    }
}
