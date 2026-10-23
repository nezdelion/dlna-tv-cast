package io.github.nezdelion.dlnatvcast.core.upnp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress

/** JDK HTTP server for description and SOAP tests; records requests it receives. */
class TestHttpServer : Closeable {
    class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    val requests = mutableListOf<Recorded>()
    val base: String get() = "http://127.0.0.1:${server.address.port}"

    fun respond(path: String, status: Int, body: String, contentType: String = "text/xml; charset=\"utf-8\"") {
        server.createContext(path) { ex: HttpExchange ->
            requests += Recorded(
                ex.requestMethod,
                ex.requestURI.path,
                ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
                ex.requestBody.readBytes().toString(Charsets.UTF_8),
            )
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", contentType)
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
    }

    fun start(): TestHttpServer = apply { server.start() }

    override fun close() = server.stop(0)
}
