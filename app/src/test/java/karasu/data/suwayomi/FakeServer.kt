package karasu.data.suwayomi

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A throwaway HTTP/1.1 server on the loopback, written on plain sockets so the tests need no
 * extra dependency (the unit-test classpath is Android's, which has no `com.sun.net.httpserver`).
 *
 * It understands exactly what OkHttp sends: a request line, headers, and a body announced by
 * `Content-Length`. Every connection is answered once and closed. Each request is recorded and
 * [handler] decides the reply.
 */
class FakeServer(private val handler: (Request) -> Reply) : AutoCloseable {

    data class Request(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, String>,
        val body: String,
    )

    data class Reply(val code: Int = 200, val body: String = "", val contentType: String = "application/json")

    val requests = CopyOnWriteArrayList<Request>()

    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    @Volatile
    private var running = true

    private val acceptor = thread(isDaemon = true, name = "fake-server") {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                break
            }
            thread(isDaemon = true) { serve(client) }
        }
    }

    val url: String get() = "http://127.0.0.1:${socket.localPort}"

    private fun serve(client: Socket) {
      client.use {
        try {
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(input) ?: return@use
            val (method, target) = requestLine.split(' ').let { it[0] to it[1] }
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input)
                if (line.isNullOrEmpty()) break
                headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length).also { buffer ->
                var read = 0
                while (read < length) {
                    val n = input.read(buffer, read, length - read)
                    if (n < 0) break
                    read += n
                }
            }.decodeToString()

            val request = Request(
                method = method,
                path = target.substringBefore('?'),
                query = target.substringAfter('?', "").ifEmpty { null },
                headers = headers,
                body = body,
            )
            requests += request
            val reply = handler(request)
            val bytes = reply.body.toByteArray()
            val head = buildString {
                append("HTTP/1.1 ${reply.code} X\r\n")
                append("Content-Type: ${reply.contentType}\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            client.getOutputStream().apply {
                write(head.toByteArray())
                // A HEAD answer announces the length and carries no body.
                if (method != "HEAD") write(bytes)
                flush()
            }
        } catch (e: Exception) {
            // A client that hung up mid-request is not the test's concern.
        }
      }
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1)
            if (b == '\n'.code) return line.toString(Charsets.ISO_8859_1).trimEnd('\r')
            line.write(b)
        }
    }

    override fun close() {
        running = false
        socket.close()
        acceptor.interrupt()
    }

    companion object {
        /** A loopback address nothing listens on: connecting fails at once with "connection refused". */
        fun deadUrl(): String {
            val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
            return "http://127.0.0.1:$port"
        }
    }
}
