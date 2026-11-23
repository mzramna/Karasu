package karasu.data.suwayomi

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ServerTransportTest {

    private var clock = 0L
    private val reachability = ServerReachability { clock }
    private val good = mutableListOf<ServerOrigin>()

    private fun transport(config: ServerConfig) = ServerTransport(
        config = { config },
        baseClient = OkHttpClient(),
        reachability = reachability,
        onGoodOrigin = { good += it },
        now = { clock },
    )

    private fun get(t: ServerTransport) = runBlocking {
        t.execute { Request.Builder().url(it.endpoint("health")).build() }
    }

    private fun ServerResponse.status(): Int = response.use { it.code }

    @Test
    fun `the primary answers and the secondary is never touched`() {
        FakeServer { FakeServer.Reply(body = "{}") }.use { primary ->
            FakeServer { FakeServer.Reply(body = "{}") }.use { secondary ->
                val answer = get(transport(ServerConfig(primary.url, secondary.url, "", "")))
                assertEquals(ServerOrigin.PRIMARY, answer.origin)
                answer.status()
                assertEquals(1, primary.requests.size)
                assertEquals(0, secondary.requests.size)
                assertEquals(ServerState.Online(ServerOrigin.PRIMARY), reachability.state.value)
            }
        }
    }

    @Test
    fun `an unreachable primary falls back to the secondary and remembers it`() {
        FakeServer { FakeServer.Reply(body = "{}") }.use { secondary ->
            val t = transport(ServerConfig(FakeServer.deadUrl(), secondary.url, "", ""))
            val first = get(t)
            assertEquals(ServerOrigin.SECONDARY, first.origin)
            first.status()
            assertEquals(listOf(ServerOrigin.SECONDARY), good)

            // Within the recheck window the secondary goes first, so the dead primary costs nothing.
            val cfg = ServerConfig(FakeServer.deadUrl(), secondary.url, "", "")
            assertEquals(ServerOrigin.SECONDARY, t.orderedOrigins(cfg).first().first)

            // After it, the primary is tried again so the app drifts back to the LAN.
            clock += ServerTransport.PRIMARY_RECHECK_MS + 1
            assertEquals(ServerOrigin.PRIMARY, t.orderedOrigins(cfg).first().first)
        }
    }

    @Test
    fun `landing on the secondary after a recheck restarts the stay window`() {
        FakeServer { FakeServer.Reply(body = "{}") }.use { secondary ->
            val cfg = ServerConfig(FakeServer.deadUrl(), secondary.url, "", "")
            val t = transport(cfg)
            get(t).status()
            clock += ServerTransport.PRIMARY_RECHECK_MS + 1
            get(t).status() // primary rechecked, still dead, secondary answers again
            assertEquals(ServerOrigin.SECONDARY, t.orderedOrigins(cfg).first().first)
        }
    }

    @Test
    fun `an HTTP error is an answer and does not trigger the fallback`() {
        FakeServer { FakeServer.Reply(code = 500, body = """{"error":"boom"}""") }.use { primary ->
            FakeServer { FakeServer.Reply(body = "{}") }.use { secondary ->
                val answer = get(transport(ServerConfig(primary.url, secondary.url, "", "")))
                assertEquals(500, answer.status())
                assertEquals(0, secondary.requests.size)
            }
        }
    }

    @Test
    fun `when nothing answers the server is reported offline and the failure names both addresses`() {
        val t = transport(ServerConfig(FakeServer.deadUrl(), FakeServer.deadUrl(), "", ""))
        val e = assertThrows(ServerUnreachableException::class.java) { get(t) }
        assertTrue(e.message!!.contains("primary"))
        assertTrue(e.message!!.contains("secondary"))
        assertEquals(ServerState.Offline, reachability.state.value)
        assertTrue(reachability.shouldSkipOptional())
        clock += 31_000
        assertTrue(!reachability.shouldSkipOptional())
    }

    @Test
    fun `an unset address is not configured`() {
        val t = transport(ServerConfig("", "", "", ""))
        assertTrue(!t.isConfigured)
        assertThrows(ServerNotConfiguredException::class.java) { get(t) }
    }

    @Test
    fun `credentials are sent as Basic Auth only when a user is set`() {
        FakeServer { FakeServer.Reply(body = "{}") }.use { server ->
            get(transport(ServerConfig(server.url, "", "ana", "s3cret"))).status()
            get(transport(ServerConfig(server.url, "", "", "ignored"))).status()
            val withAuth = server.requests[0].headers["authorization"]
            assertEquals("Basic YW5hOnMzY3JldA==", withAuth)
            assertNull(server.requests[1].headers["authorization"])
        }
    }

    @Test
    fun `a bare host and port gets http and a subpath is kept`() {
        assertEquals("http://192.168.1.10:4567/", ServerTransport.parseBase("192.168.1.10:4567")?.toString())
        assertEquals(
            "https://example.com/suwayomi",
            ServerTransport.parseBase(" https://example.com/suwayomi/ ")?.toString(),
        )
        assertNull(ServerTransport.parseBase("   "))
        assertNotNull(ServerTransport.parseBase("my-box.tailnet.ts.net"))
    }

    @Test
    fun `endpoints keep the subpath and encode a backup name as one segment`() {
        val base = ServerTransport.parseBase("https://example.com/suwayomi")!!
        assertEquals("https://example.com/suwayomi/api/v1/karasu/health", base.endpoint("health").toString())
        assertEquals(
            "https://example.com/suwayomi/api/v1/karasu/backups/a%20b%2Fc.tachibk/download",
            base.endpoint(listOf("backups", "a b/c.tachibk", "download")).toString(),
        )
        assertEquals("https://example.com/suwayomi/api/graphql", base.graphqlEndpoint().toString())
    }
}
