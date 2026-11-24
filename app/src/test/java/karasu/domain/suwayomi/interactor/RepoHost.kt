package karasu.domain.suwayomi.interactor

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import kotlin.concurrent.thread

/**
 * A place that plays extension repositories, so the scenarios can add and remove them without internet: it answers
 * `GET /<dir>/repo.json` with a repository index in the current format for every [publish]ed directory and 404 for
 * anything else, which is how a repository that cannot be fetched looks to whoever asks.
 *
 * It is asked by whoever validates a repository URL when it is added: the mock server, a real server (which fetches
 * it from the same machine, so the script that starts one and the tests must run together) and [LiveDevice]. Plain
 * sockets, like [MockSuwayomiServer]: the unit-test classpath is Android's and has no com.sun.net.httpserver.
 */
class RepoHost : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val bodies = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile
    private var running = true

    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true, name = "repo-host") {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) = client.use {
        val reader = it.getInputStream().bufferedReader()
        val path = reader.readLine().orEmpty().split(' ').getOrElse(1) { "/" }.substringBefore('?')
        while (reader.readLine().orEmpty().isNotEmpty()) { /* the headers: nothing in them matters */ }
        val body = bodies[path]
        val out = it.getOutputStream()
        if (body == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            val bytes = body.toByteArray()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes)
        }
        out.flush()
    }

    /** A repository in `/<dir>`; returns the base URL the app keeps for it (the index is `$base/repo.json`). */
    fun publish(dir: String, name: String = "Repository $dir"): String {
        bodies["/$dir/repo.json"] =
            """{"name":"$name","badgeLabel":"R","signingKey":"key-$dir","contact":{"website":"https://example.test","discord":null},""" +
            """"extensionList":null,"extensionListUrl":null}"""
        return "$baseUrl/$dir"
    }

    /** The repository stops answering, as when its owner takes it down. */
    fun unpublish(dir: String) {
        bodies.remove("/$dir/repo.json")
    }

    override fun close() {
        running = false
        socket.close()
    }

    companion object {
        /** Whether [indexUrl] answers 200, which is all the mock server (and the app, in tests) ask of a repository. */
        fun answers(indexUrl: String): Boolean = try {
            val connection = URI(indexUrl).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.responseCode == 200
        } catch (e: Exception) {
            false
        }
    }
}
