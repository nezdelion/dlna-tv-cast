package io.github.nezdelion.dlnatvcast.core.http

import io.github.nezdelion.dlnatvcast.core.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.util.Locale
import java.util.concurrent.Executors

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
    /** Absolute time a new connection has to deliver its first request headers. */
    private val headerDeadlineMs: Long = 10_000,
    /** How long a keep-alive connection may sit idle between requests. */
    private val keepAliveIdleMs: Long = 30_000,
    /** Connections that have not yet shown the session token; the oldest is dropped beyond this. */
    private val maxPending: Int = 16,
    /** Connections serving items to the renderer; more are answered with 503. */
    private val maxActive: Int = 16,
) : Closeable {

    val token: String = randomToken()

    // Own threads, not Dispatchers.IO: LAN clients must not be able to starve SOAP or other app I/O.
    // Thread count is bounded by maxPending + maxActive + the accept loop.
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "dlnatvcast-media-server").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val lock = Any()
    private val pending = LinkedHashSet<Socket>()
    private val active = HashSet<Socket>()
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
        synchronized(lock) {
            (pending + active).forEach { runCatching { it.close() } }
            pending.clear()
            active.clear()
        }
        scope.cancel()
        executor.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            synchronized(lock) {
                if (pending.size >= maxPending) {
                    // Keep accepting: drop the connection that has waited longest without a request.
                    val oldest = pending.first()
                    pending.remove(oldest)
                    runCatching { oldest.close() }
                }
                pending += client
            }
            scope.launch {
                try {
                    client.use { serve(it) }
                } catch (_: IOException) {
                    // Client went away mid-transfer; renderers do this on every seek.
                } catch (_: RuntimeException) {
                    // The source failed (e.g. a revoked content grant); drop this connection only.
                } finally {
                    synchronized(lock) {
                        pending -= client
                        active -= client
                    }
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.tcpNoDelay = true
        val deadlineInput = DeadlineInputStream(socket)
        val input = BufferedInputStream(deadlineInput)
        val output = BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE)
        var first = true
        while (true) {
            // Absolute deadlines: a client trickling bytes cannot hold the connection open.
            deadlineInput.deadlineNanos = System.nanoTime() +
                (if (first) headerDeadlineMs else keepAliveIdleMs + headerDeadlineMs) * 1_000_000
            val request = try {
                readRequest(input)
            } catch (_: SocketException) {
                null
            } catch (_: java.net.SocketTimeoutException) {
                null
            } ?: return
            deadlineInput.deadlineNanos = null
            if (first) {
                first = false
                if (!admit(socket, request, output)) return
            }
            val keepAlive = handle(request, output)
            output.flush()
            if (!keepAlive) return
        }
    }

    /**
     * Moves a connection that presented the session token from pending to active. Connections
     * without a valid token get their 404 and are closed rather than kept alive.
     */
    private fun admit(socket: Socket, request: Request, out: OutputStream): Boolean {
        if (resolve(request.path) == null) {
            handle(request, out, forceClose = true)
            out.flush()
            return false
        }
        val admitted = synchronized(lock) {
            pending -= socket
            if (active.size >= maxActive) false else active.add(socket)
        }
        if (!admitted) {
            writeHead(out, 503, "Service Unavailable", listOf("Content-Length" to "0"), keepAlive = false)
            out.flush()
        }
        return admitted
    }

    /** Socket input whose reads fail once [deadlineNanos] has passed (null = no deadline). */
    private class DeadlineInputStream(private val socket: Socket) : InputStream() {
        private val input = socket.getInputStream()
        @Volatile var deadlineNanos: Long? = null

        private fun arm() {
            val deadline = deadlineNanos
            if (deadline == null) {
                socket.soTimeout = 0
                return
            }
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) throw java.net.SocketTimeoutException("Request deadline passed")
            socket.soTimeout = remainingMs.toInt().coerceAtLeast(1)
        }

        override fun read(): Int {
            arm()
            return input.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            arm()
            return input.read(b, off, len)
        }

        override fun close() = input.close()
    }

    /** Returns whether the connection may be reused. */
    private fun handle(request: Request, out: OutputStream, forceClose: Boolean = false): Boolean {
        var keepAlive = request.wantsKeepAlive && !forceClose
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
        // Ranges (and so DLNA byte seek) only work when the length is known.
        if (length != null) headers += "Accept-Ranges" to "bytes"
        val askedFeatures = request.header("getcontentfeatures.dlna.org") == "1"
        if (askedFeatures) headers += "contentFeatures.dlna.org" to Dlna.contentFeatures(item.kind, byteSeek = length != null)
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
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 100
        private const val SERVER_HEADER = "Android UPnP/1.0 dlna-tv-cast/1.0"

        private fun randomToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
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
