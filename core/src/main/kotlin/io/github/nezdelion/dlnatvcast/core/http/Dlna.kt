package io.github.nezdelion.dlnatvcast.core.http

import io.github.nezdelion.dlnatvcast.core.MediaKind

/** DLNA header and protocolInfo values shared by the HTTP server and DIDL-Lite metadata. */
object Dlna {
    // Streaming transfer + background + connection stall + DLNA 1.5.
    private const val FLAGS_STREAMING = "01700000000000000000000000000000"
    // Interactive transfer + background + DLNA 1.5.
    private const val FLAGS_INTERACTIVE = "00D00000000000000000000000000000"

    /**
     * `DLNA.ORG_OP=01` advertises byte-range seeking, which the server only offers when the
     * item's length is known ([byteSeek]); otherwise `00`.
     */
    fun contentFeatures(kind: MediaKind, byteSeek: Boolean): String {
        val flags = if (kind == MediaKind.IMAGE) FLAGS_INTERACTIVE else FLAGS_STREAMING
        val op = if (byteSeek) "01" else "00"
        return "DLNA.ORG_OP=$op;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=$flags"
    }

    fun transferMode(kind: MediaKind): String =
        if (kind == MediaKind.IMAGE) "Interactive" else "Streaming"

    fun protocolInfo(mime: String, kind: MediaKind, byteSeek: Boolean): String =
        "http-get:*:$mime:${contentFeatures(kind, byteSeek)}"
}
