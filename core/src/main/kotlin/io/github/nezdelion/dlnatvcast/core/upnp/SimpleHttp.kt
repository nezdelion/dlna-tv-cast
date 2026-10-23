package io.github.nezdelion.dlnatvcast.core.upnp

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Minimal HTTP/1.1 client on raw sockets for device descriptions and SOAP.
 *
 * Every request has a total deadline and is cancellable: the blocking exchange runs on its own
 * thread and cancellation closes the socket, which aborts a blocked connect or read at once.
 * (`HttpURLConnection.disconnect()` cannot do that reliably while another thread reads.)
 * Bodies larger than [MAX_BODY_BYTES] are refused. Only plain `http` is supported, which is
 * what UPnP devices on a LAN use.
 */
internal object SimpleHttp {
    const val MAX_BODY_BYTES = 256 * 1024
    private const val MAX_HEADER_LINE = 8 * 1024
    private const val MAX_HEADERS = 100

    class Response(val status: Int, val body: String)

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "dlnatvcast-http").apply { isDaemon = true }
    }

    suspend fun get(url: String, timeoutMs: Int): Response = exchange(url, "GET", emptyMap(), null, timeoutMs)

    suspend fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): Response =
        exchange(url, "POST", headers, body.toByteArray(Charsets.UTF_8), timeoutMs)

    private suspend fun exchange(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMs: Int,
    ): Response {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw IOException("Invalid URL: $url", e)
        }
        if (uri.scheme != "http" || uri.host.isNullOrEmpty()) throw IOException("Not an http URL: $url")
        val socket = Socket()
        return withTimeoutOrNull(timeoutMs.toLong()) {
            suspendCancellableCoroutine { cont ->
                executor.execute {
                    try {
                        cont.resume(perform(socket, uri, method, headers, body, timeoutMs))
                    } catch (e: Throwable) {
                        cont.resumeWithException(e)
                    } finally {
                        runCatching { socket.close() }
                    }
                }
                cont.invokeOnCancellation { runCatching { socket.close() } }
            }
        } ?: throw SocketTimeoutException("No complete response from $url within $timeoutMs ms")
    }

    private fun perform(
        socket: Socket,
        uri: URI,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMs: Int,
    ): Response {
        val port = if (uri.port > 0) uri.port else 80
        socket.connect(InetSocketAddress(uri.host, port), timeoutMs)
        socket.soTimeout = timeoutMs
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val head = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(uri.host).append(':').append(port).append("\r\n")
            append("Connection: close\r\n")
            append("User-Agent: Android UPnP/1.0 dlna-tv-cast/1.0\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            if (body != null) append("Content-Length: ").append(body.size).append("\r\n")
            append("\r\n")
        }
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            if (body != null) write(body)
            flush()
        }
        val input = BufferedInputStream(socket.getInputStream())
        val status = readLine(input).split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw IOException("Malformed HTTP status line")
        val responseHeaders = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            if (responseHeaders.size >= MAX_HEADERS) throw IOException("Too many headers")
            val colon = line.indexOf(':')
            if (colon > 0) responseHeaders[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val bytes = when {
            method == "HEAD" || status == 204 || status == 304 -> ByteArray(0)
            responseHeaders["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            responseHeaders["content-length"] != null -> {
                val length = responseHeaders["content-length"]!!.toLongOrNull() ?: throw IOException("Bad Content-Length")
                if (length > MAX_BODY_BYTES) throw IOException("Response too large")
                readExactly(input, length.toInt())
            }
            else -> readToEnd(input)
        }
        return Response(status, bytes.toString(Charsets.UTF_8))
    }

    private fun readLine(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("Connection closed in headers")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > MAX_HEADER_LINE) throw IOException("Header line too long")
        }
        return out.toString(Charsets.ISO_8859_1.name())
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val bytes = input.readNBytes(length)
        if (bytes.size < length) throw IOException("Body ended early")
        return bytes
    }

    private fun readToEnd(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > MAX_BODY_BYTES) throw IOException("Response too large")
            out.write(buffer, 0, read)
        }
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input).substringBefore(';').trim().toIntOrNull(16)
                ?: throw IOException("Bad chunk size")
            if (size == 0) {
                while (readLine(input).isNotEmpty()) Unit // trailers
                return out.toByteArray()
            }
            if (out.size() + size > MAX_BODY_BYTES) throw IOException("Response too large")
            out.write(readExactly(input, size))
            readLine(input)
        }
    }
}
