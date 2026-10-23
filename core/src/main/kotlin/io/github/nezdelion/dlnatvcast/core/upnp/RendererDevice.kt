package io.github.nezdelion.dlnatvcast.core.upnp

import java.io.IOException
import java.net.URI

/** A MediaRenderer that exposes AVTransport, as read from its device description. */
data class RendererDevice(
    val udn: String,
    val friendlyName: String,
    /** Description URL the device was read from. */
    val location: String,
    val avTransportControlUrl: String,
    val renderingControlUrl: String?,
    val avTransportType: String = AV_TRANSPORT_V1,
    val renderingControlType: String = RENDERING_CONTROL_V1,
) {
    companion object {
        const val AV_TRANSPORT_V1 = "urn:schemas-upnp-org:service:AVTransport:1"
        const val RENDERING_CONTROL_V1 = "urn:schemas-upnp-org:service:RenderingControl:1"
    }
}

class InvalidRendererException(message: String, cause: Throwable? = null) : IOException(message, cause)

object DeviceDescription {
    private const val RENDERER_PREFIX = "urn:schemas-upnp-org:device:MediaRenderer:"
    private const val AVT_PREFIX = "urn:schemas-upnp-org:service:AVTransport:"
    private const val RCS_PREFIX = "urn:schemas-upnp-org:service:RenderingControl:"

    /**
     * Parses a device description fetched from [location]. Picks the first MediaRenderer
     * device (root or embedded) that has an AVTransport service.
     *
     * @throws InvalidRendererException when the XML is malformed or describes no usable renderer.
     */
    fun parse(xml: String, location: String): RendererDevice {
        val root = try {
            Xml.parse(xml).documentElement
        } catch (e: Exception) {
            throw InvalidRendererException("Device description is not valid XML", e)
        }
        val base = root.childText("URLBase")?.takeIf { it.isNotEmpty() } ?: location
        return try {
            findRenderer(root, base, location)
        } catch (e: InvalidRendererException) {
            throw e
        } catch (e: Exception) {
            // Malformed URLs (URISyntaxException, IllegalArgumentException) and anything else odd.
            throw InvalidRendererException("Invalid device description", e)
        }
    }

    private fun findRenderer(root: org.w3c.dom.Element, base: String, location: String): RendererDevice {
        val devices = buildList {
            root.child("device")?.let { add(it) }
            addAll(root.descendants("device").filterNot { it == root.child("device") })
        }
        for (device in devices) {
            val type = device.childText("deviceType") ?: continue
            if (!type.startsWith(RENDERER_PREFIX)) continue
            val services = device.child("serviceList")?.children().orEmpty()
            val avt = services.firstOrNull { it.childText("serviceType")?.startsWith(AVT_PREFIX) == true }
                ?: continue
            val rcs = services.firstOrNull { it.childText("serviceType")?.startsWith(RCS_PREFIX) == true }
            val udn = device.childText("UDN")?.takeIf { it.isNotEmpty() }
                ?: throw InvalidRendererException("Renderer has no UDN")
            val avtControl = avt.childText("controlURL")?.takeIf { it.isNotEmpty() }
                ?: throw InvalidRendererException("AVTransport has no controlURL")
            return RendererDevice(
                udn = udn,
                friendlyName = device.childText("friendlyName")?.takeIf { it.isNotEmpty() } ?: udn,
                location = location,
                avTransportControlUrl = httpUrl(base, avtControl),
                renderingControlUrl = rcs?.childText("controlURL")?.takeIf { it.isNotEmpty() }?.let { httpUrl(base, it) },
                avTransportType = avt.childText("serviceType")!!,
                renderingControlType = rcs?.childText("serviceType") ?: RendererDevice.RENDERING_CONTROL_V1,
            )
        }
        throw InvalidRendererException("Not a MediaRenderer with AVTransport")
    }

    /** Fetches and parses [location]. */
    suspend fun fetch(location: String, timeoutMs: Int = 3000): RendererDevice {
        val uri = try {
            URI(location.trim())
        } catch (e: Exception) {
            throw InvalidRendererException("Invalid URL", e)
        }
        if (uri.scheme != "http" || uri.host.isNullOrEmpty()) {
            throw InvalidRendererException("URL must start with http://")
        }
        val response = try {
            SimpleHttp.get(uri.toString(), timeoutMs)
        } catch (e: IOException) {
            throw InvalidRendererException("No response from ${uri.host}", e)
        }
        if (response.status != 200) throw InvalidRendererException("HTTP ${response.status}")
        return parse(response.body, uri.toString())
    }

    /** Resolves [reference] against [base] and insists on an absolute http URL with a host. */
    private fun httpUrl(base: String, reference: String): String {
        val resolved = resolve(base, reference)
        val uri = try {
            URI(resolved)
        } catch (e: Exception) {
            throw InvalidRendererException("Invalid service URL", e)
        }
        if (uri.scheme != "http" || uri.host.isNullOrEmpty()) {
            throw InvalidRendererException("Service URL must be http: $resolved")
        }
        return resolved
    }

    internal fun resolve(base: String, reference: String): String {
        val ref = reference.trim()
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        val baseUri = URI(base.trim())
        // A URLBase without a trailing path component ("http://host:port") resolves like "/".
        val normalised = if (baseUri.rawPath.isNullOrEmpty()) URI("$baseUri/") else baseUri
        return normalised.resolve(ref).toString()
    }
}
