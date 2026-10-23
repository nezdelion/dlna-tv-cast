package io.github.nezdelion.dlnatvcast.core.upnp

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Blocking HTTP helpers on top of [HttpURLConnection]; enough for descriptions and SOAP. */
internal object SimpleHttp {
    class Response(val status: Int, val body: String)

    fun get(url: String, timeoutMs: Int): Response = exchange(url, "GET", emptyMap(), null, timeoutMs)

    fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): Response =
        exchange(url, "POST", headers, body.toByteArray(Charsets.UTF_8), timeoutMs)

    private fun exchange(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMs: Int,
    ): Response {
        val connection = URI(url).toURL().openConnection() as? HttpURLConnection
            ?: throw IOException("Not an HTTP URL: $url")
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = method
            connection.useCaches = false
            connection.instanceFollowRedirects = true
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return Response(status, text)
        } finally {
            connection.disconnect()
        }
    }
}
