package io.github.nezdelion.dlnatvcast.core.upnp

import java.io.IOException

/** A UPnP fault returned by a device (`UPnPError` in the SOAP fault detail). */
class UpnpException(val code: Int?, val description: String) :
    IOException(if (code != null) "UPnP error $code: $description" else description)

class SoapClient(private val timeoutMs: Int = 5000) {

    /** Invokes [action] and returns its out arguments by name. */
    fun call(controlUrl: String, serviceType: String, action: String, args: List<Pair<String, String>>): Map<String, String> {
        val response = SimpleHttp.post(
            controlUrl,
            mapOf(
                "Content-Type" to "text/xml; charset=\"utf-8\"",
                "SOAPACTION" to "\"$serviceType#$action\"",
            ),
            envelope(serviceType, action, args),
            timeoutMs,
        )
        return parseResponse(response.status, response.body, action)
    }

    companion object {
        fun envelope(serviceType: String, action: String, args: List<Pair<String, String>>): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:").append(action).append(" xmlns:u=\"").append(Xml.escape(serviceType)).append("\">")
            for ((name, value) in args) {
                append('<').append(name).append('>').append(Xml.escape(value)).append("</").append(name).append('>')
            }
            append("</u:").append(action).append("></s:Body></s:Envelope>")
        }

        fun parseResponse(status: Int, body: String, action: String): Map<String, String> {
            val doc = try {
                if (body.isBlank()) null else Xml.parse(body)
            } catch (_: Exception) {
                null
            }
            val soapBody = doc?.documentElement?.child("Body")
            val fault = soapBody?.child("Fault")
            if (fault != null) {
                val error = fault.descendants("UPnPError").firstOrNull()
                val code = error?.childText("errorCode")?.toIntOrNull()
                val description = error?.childText("errorDescription")?.takeIf { it.isNotEmpty() }
                    ?: fault.childText("faultstring")
                    ?: "SOAP fault"
                throw UpnpException(code, description)
            }
            if (status != 200) throw IOException("HTTP $status from renderer")
            val result = soapBody?.child("${action}Response")
                ?: throw IOException("Malformed SOAP response for $action")
            return result.children().associate { it.localTag to it.textContent }
        }
    }
}
