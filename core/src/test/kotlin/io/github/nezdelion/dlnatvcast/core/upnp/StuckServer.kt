package io.github.nezdelion.dlnatvcast.core.upnp

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Accepts connections and never answers: a renderer that hangs mid-request. */
class StuckServer : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val clients = CopyOnWriteArrayList<Socket>()
    val base: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true) {
            try {
                while (true) clients += server.accept()
            } catch (_: Exception) {
            }
        }
    }

    override fun close() {
        server.close()
        clients.forEach { runCatching { it.close() } }
    }
}

/** Answers with headers and then trickles one body byte every 100 ms, never tripping a read timeout. */
class TrickleServer : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val base: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true) {
            try {
                while (true) {
                    val s = server.accept()
                    thread(isDaemon = true) {
                        try {
                            s.getInputStream().read(ByteArray(4096))
                            val out = s.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: 100000\r\n\r\n".toByteArray())
                            repeat(100_000) { out.write(' '.code); out.flush(); Thread.sleep(100) }
                        } catch (_: Exception) {
                        } finally {
                            runCatching { s.close() }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun close() = server.close()
}
