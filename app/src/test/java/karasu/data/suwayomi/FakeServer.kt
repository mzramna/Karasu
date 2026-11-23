package karasu.data.suwayomi

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A throwaway HTTP server on the loopback, built on the JDK so the tests need no extra dependency.
 * Every request it sees is recorded, and [handler] decides the answer.
 */
class FakeServer(private val handler: (Request) -> Reply) : AutoCloseable {

    data class Request(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: String)

    data class Reply(val code: Int = 200, val body: String = "", val contentType: String = "application/json")

    val requests = CopyOnWriteArrayList<Request>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange -> serve(exchange) }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    private fun serve(exchange: HttpExchange) {
        val request = Request(
            method = exchange.requestMethod,
            path = exchange.requestURI.rawPath,
            query = exchange.requestURI.rawQuery,
            headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
            body = exchange.requestBody.readBytes().decodeToString(),
        )
        requests += request
        val reply = handler(request)
        val bytes = reply.body.toByteArray()
        exchange.responseHeaders.add("Content-Type", reply.contentType)
        // HEAD must announce a length but carry no body.
        if (exchange.requestMethod == "HEAD") {
            exchange.responseHeaders.add("Content-Length", bytes.size.toString())
            exchange.sendResponseHeaders(reply.code, -1)
        } else {
            exchange.sendResponseHeaders(reply.code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        }
        exchange.close()
    }

    override fun close() = server.stop(0)

    companion object {
        /** A loopback address nothing listens on: connecting fails at once with "connection refused". */
        fun deadUrl(): String {
            val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
            return "http://127.0.0.1:$port"
        }
    }
}
