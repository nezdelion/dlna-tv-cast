package io.github.nezdelion.dlnatvcast.core.http

import io.github.nezdelion.dlnatvcast.core.MediaKind

/** DLNA header and protocolInfo values shared by the HTTP server and DIDL-Lite metadata. */
object Dlna {
    // Streaming transfer + background + connection stall + DLNA 1.5.
    private const val FLAGS_STREAMING = "01700000000000000000000000000000"
    // Interactive transfer + background + DLNA 1.5.
    private const val FLAGS_INTERACTIVE = "00D00000000000000000000000000000"

    /** `DLNA.ORG_OP=01` advertises byte-range seeking. */
    fun contentFeatures(kind: MediaKind): String {
        val flags = if (kind == MediaKind.IMAGE) FLAGS_INTERACTIVE else FLAGS_STREAMING
        return "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=$flags"
    }

    fun transferMode(kind: MediaKind): String =
        if (kind == MediaKind.IMAGE) "Interactive" else "Streaming"

    fun protocolInfo(mime: String, kind: MediaKind): String = "http-get:*:$mime:${contentFeatures(kind)}"
}
