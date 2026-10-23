package io.github.nezdelion.dlnatvcast.core.http

import io.github.nezdelion.dlnatvcast.core.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Collections

/**
 * Session-scoped HTTP/1.1 server that exposes [items] under `/<token>/<index>/<name>`.
 *
 * One instance lives exactly as long as one cast session; [close] stops listening and drops
 * open connections so old URLs stop working.
 *
 * @param advertisedAuthority `host:port` to put into URLs instead of the bound address
 *   (debug builds use it behind emulator port forwarding).
 */
class MediaServer(
    private val items: List<MediaItem>,
    private val bindAddress: InetAddress,
    private val port: Int = 0,
    private val advertisedAuthority: String? = null,
) : Closeable {

    val token: String = randomToken()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = Collections.synchronizedSet(mutableSetOf<Socket>())
    private var serverSocket: ServerSocket? = null

    val localPort: Int get() = serverSocket?.localPort ?: error("Server not started")

    fun start(): MediaServer {
        check(serverSocket == null) { "Already started" }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bindAddress, port))
        serverSocket = socket
        scope.launch { acceptLoop(socket) }
        return this
    }

    fun urlFor(index: Int): String {
        val authority = advertisedAuthority ?: "${hostLiteral(bindAddress)}:$localPort"
        return "http://$authority/$token/$index/${fileName(items[index])}"
    }

    override fun close() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        synchronized(connections) {
            connections.forEach { runCatching { it.close() } }
            connections.clear()
        }
        scope.cancel()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            connections += client
            scope.launch {
                try {
                    client.use { serve(it) }
                } catch (_: IOException) {
                    // Client went away mid-transfer; renderers do this on every seek.
                } finally {
                    connections -= client
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = IDLE_TIMEOUT_MS
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE)
        while (true) {
            val request = try {
                readRequest(input)
            } catch (_: SocketException) {
                null
            } catch (_: java.net.SocketTimeoutException) {
                null
            } ?: return
            val keepAlive = handle(request, output)
            output.flush()
            if (!keepAlive) return
        }
    }

    /** Returns whether the connection may be reused. */
    private fun handle(request: Request, out: OutputStream): Boolean {
        var keepAlive = request.wantsKeepAlive
        if (request.method != "GET" && request.method != "HEAD") {
            writeHead(out, 501, "Not Implemented", listOf("Content-Length" to "0"), keepAlive)
            return keepAlive
        }
        val item = resolve(request.path)
        if (item == null) {
            val body = "Not Found".toByteArray()
            writeHead(
                out, 404, "Not Found",
                listOf("Content-Type" to "text/plain", "Content-Length" to body.size.toString()),
                keepAlive,
            )
            if (request.method == "GET") out.write(body)
            return keepAlive
        }

        val source = item.source
        val length = source.length
        val headers = mutableListOf("Content-Type" to source.mime)
        if (length != null) headers += "Accept-Ranges" to "bytes"
        val askedFeatures = request.header("getcontentfeatures.dlna.org") == "1"
        if (askedFeatures) headers += "contentFeatures.dlna.org" to Dlna.contentFeatures(item.kind)
        if (askedFeatures || request.header("transfermode.dlna.org") != null) {
            headers += "transferMode.dlna.org" to Dlna.transferMode(item.kind)
        }

        when (val range = ByteRange.parse(request.header("range"), length)) {
            ByteRange.Unsatisfiable -> {
                headers += "Content-Range" to "bytes */$length"
                headers += "Content-Length" to "0"
                writeHead(out, 416, "Range Not Satisfiable", headers, keepAlive)
            }

            is ByteRange.Partial -> {
                headers += "Content-Range" to "bytes ${range.start}-${range.end}/$length"
                headers += "Content-Length" to range.count.toString()
                writeHead(out, 206, "Partial Content", headers, keepAlive)
                if (request.method == "GET") copy(source, range.start, range.count, out)
            }

            ByteRange.Full -> {
                if (length != null) {
                    headers += "Content-Length" to length.toString()
                } else {
                    // Without a length the end of the body is the end of the connection.
                    keepAlive = false
                }
                writeHead(out, 200, "OK", headers, keepAlive)
                if (request.method == "GET") copy(source, 0, length, out)
            }
        }
        return keepAlive
    }

    private fun resolve(path: String): MediaItem? {
        val parts = path.substringBefore('?').trimStart('/').split('/')
        if (parts.size < 2 || parts[0] != token) return null
        val index = parts[1].toIntOrNull() ?: return null
        return items.getOrNull(index)
    }

    private fun copy(source: ByteSource, offset: Long, count: Long?, out: OutputStream) {
        source.open(offset).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var remaining = count ?: Long.MAX_VALUE
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
            if (count != null && remaining > 0) {
                throw IOException("Source ended $remaining bytes early")
            }
        }
    }

    private fun writeHead(
        out: OutputStream,
        status: Int,
        reason: String,
        headers: List<Pair<String, String>>,
        keepAlive: Boolean,
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            append("Server: ").append(SERVER_HEADER).append("\r\n")
            append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    private class Request(
        val method: String,
        val path: String,
        val version: String,
        private val headers: Map<String, String>,
    ) {
        fun header(name: String): String? = headers[name]

        val wantsKeepAlive: Boolean
            get() {
                val connection = header("connection")?.lowercase()
                return if (version == "HTTP/1.0") connection == "keep-alive" else connection != "close"
            }
    }

    private fun readRequest(input: InputStream): Request? {
        var line = readLine(input) ?: return null
        // Tolerate stray CRLF between pipelined requests.
        while (line.isEmpty()) line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size != 3) throw IOException("Bad request line")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = readLine(input) ?: return null
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon <= 0) continue
            headers[header.substring(0, colon).trim().lowercase()] = header.substring(colon + 1).trim()
            if (headers.size > MAX_HEADERS) throw IOException("Too many headers")
        }
        return Request(parts[0].uppercase(), parts[1], parts[2].uppercase(), headers)
    }

    private fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.write(b)
            if (bytes.size() > MAX_LINE) throw IOException("Line too long")
        }
        return bytes.toString(Charsets.ISO_8859_1.name())
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val IDLE_TIMEOUT_MS = 60_000
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 100
        private const val SERVER_HEADER = "Android UPnP/1.0 dlna-tv-cast/1.0"

        private fun randomToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun hostLiteral(address: InetAddress): String {
            val host = address.hostAddress
            return if (host.contains(':')) "[$host]" else host
        }

        internal fun fileName(item: MediaItem): String {
            val ext = extensionFor(item.mime)
            val base = item.title.substringBeforeLast('.', item.title)
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trim('_', '.')
                .take(60)
                .ifEmpty { "media" }
            return "$base.$ext"
        }

        private fun extensionFor(mime: String): String = when (val m = mime.lowercase()) {
            "image/jpeg" -> "jpg"
            "video/quicktime" -> "mov"
            "video/x-matroska" -> "mkv"
            "audio/mpeg" -> "mp3"
            "audio/mp4" -> "m4a"
            "video/3gpp" -> "3gp"
            else -> m.substringAfter('/').substringBefore(';').replace(Regex("[^a-z0-9]"), "").ifEmpty { "bin" }
        }
    }
}
