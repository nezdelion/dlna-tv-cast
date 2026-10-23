package io.github.nezdelion.dlnatvcast.core.upnp

import io.github.nezdelion.dlnatvcast.core.MediaKind
import io.github.nezdelion.dlnatvcast.core.http.Dlna

object DidlLite {
    fun upnpClass(kind: MediaKind): String = when (kind) {
        MediaKind.IMAGE -> "object.item.imageItem.photo"
        MediaKind.VIDEO -> "object.item.videoItem"
        MediaKind.AUDIO -> "object.item.audioItem.musicTrack"
    }

    /** Metadata for one item, as passed in `CurrentURIMetaData`. */
    fun item(title: String, url: String, mime: String, kind: MediaKind, size: Long?): String = buildString {
        append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
        append("<item id=\"1\" parentID=\"0\" restricted=\"1\">")
        append("<dc:title>").append(Xml.escape(title)).append("</dc:title>")
        append("<upnp:class>").append(upnpClass(kind)).append("</upnp:class>")
        append("<res protocolInfo=\"").append(Xml.escape(Dlna.protocolInfo(mime, kind))).append('"')
        if (size != null) append(" size=\"").append(size).append('"')
        append('>').append(Xml.escape(url)).append("</res>")
        append("</item></DIDL-Lite>")
    }
}
