package io.github.nezdelion.tvcast.core.http

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket

/** Minimal keep-alive capable HTTP client so tests see exactly what goes over the socket. */
class RawHttpClient(port: Int) : Closeable {
    private val socket = Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 5_000 }
    private val input = BufferedInputStream(socket.getInputStream())

    class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    fun request(method: String, path: String, vararg headers: Pair<String, String>): Response {
        val text = buildString {
            append("$method $path HTTP/1.1\r\nHost: test\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().apply { write(text.toByteArray()); flush() }
        val status = readLine(input).split(' ')[1].toInt()
        val responseHeaders = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            responseHeaders[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        val length = responseHeaders["content-length"]?.toInt()
        val body = when {
            method == "HEAD" -> ByteArray(0)
            length != null -> input.readNBytes(length)
            else -> input.readAllBytes()
        }
        return Response(status, responseHeaders, body)
    }

    override fun close() = socket.close()

    private fun readLine(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            check(b >= 0) { "Connection closed" }
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(Charsets.ISO_8859_1)
    }
}
