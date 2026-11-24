package karasu.data.koreader

import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.every
import io.mockk.mockk
import karasu.data.suwayomi.FakeServer
import karasu.data.suwayomi.ServerConfig
import karasu.data.suwayomi.ServerReachability
import karasu.data.suwayomi.ServerSyncApi
import karasu.data.suwayomi.ServerTransport
import karasu.domain.koreader.KoreaderPreferences
import karasu.domain.suwayomi.interactor.InMemoryPreferenceStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shelf has two homes: a container of its own (Bearer key, `/api/shelf`) and the Suwayomi server
 * (Basic login with LAN/fallback, `/api/v1/karasu/shelf`). Which one is used is one switch.
 */
class KoreaderApiTest {

    private val preferences = KoreaderPreferences(InMemoryPreferenceStore())

    private fun server(primary: String, secondary: String = ""): ServerSyncApi {
        val reachability = ServerReachability()
        return ServerSyncApi(
            ServerTransport({ ServerConfig(primary, secondary, "karasu", "s3cret") }, OkHttpClient(), reachability),
            reachability,
        )
    }

    private fun api(server: ServerSyncApi): KoreaderApi {
        val network = mockk<NetworkHelper>()
        every { network.client } returns OkHttpClient()
        return KoreaderApi(preferences, network, server)
    }

    private val shelfBody = """{"entries":[{"chapterId":3,"chapterUrl":"/c/3","read":true}]}"""

    @Test
    fun `with the switch off the shelf is the container's, with its bearer key`() = runBlocking {
        FakeServer { FakeServer.Reply(200, shelfBody) }.use { container ->
            preferences.serverUrl().set(container.url + "/")
            preferences.apiKey().set("key-123")

            val api = api(server("http://127.0.0.1:1"))
            assertFalse(api.usesSuwayomi)
            assertTrue(api.isConfigured)
            // reading the answer needs the app's Koin (not started in a unit test); the request is what matters here
            runCatching { api.shelf() }

            val request = container.requests.single()
            assertEquals("/api/shelf", request.path)
            assertEquals("Bearer key-123", request.headers["authorization"])
        }
    }

    @Test
    fun `with the switch on the shelf is the Suwayomi server's, with its login and none of the container's settings`() = runBlocking {
        FakeServer { FakeServer.Reply(200, shelfBody) }.use { suwayomi ->
            preferences.useSuwayomi().set(true)
            preferences.serverUrl().set("http://container.invalid") // must not be used
            preferences.apiKey().set("ignored")

            val api = api(server(suwayomi.url))
            assertTrue(api.usesSuwayomi)
            assertEquals(listOf(3L), api.shelf().map { it.chapterId })
            api.delete(3)

            assertEquals(listOf("/api/v1/karasu/shelf", "/api/v1/karasu/shelf/3"), suwayomi.requests.map { it.path })
            assertEquals("DELETE", suwayomi.requests.last().method)
            suwayomi.requests.forEach {
                assertTrue(it.headers["authorization"]!!.startsWith("Basic "), "the server's login, not the container's key")
            }
        }
    }

    @Test
    fun `in Suwayomi mode the shelf is configured by the server address alone, and follows the fallback`() = runBlocking {
        preferences.useSuwayomi().set(true)
        assertFalse(api(server("")).isConfigured, "no server address, nothing to do")

        FakeServer { FakeServer.Reply(200, shelfBody) }.use { reachable ->
            val api = api(server(FakeServer.deadUrl(), reachable.url))
            assertTrue(api.isConfigured)
            assertEquals(listOf(3L), api.shelf().map { it.chapterId }, "the LAN address is down, the fallback answers")
        }
    }

    @Test
    fun `the connection check in Suwayomi mode reports the server and its database`() = runBlocking {
        val health = """{"status":"ok","serverVersion":"v2.4.9","apiVersion":1,"database":{"type":"POSTGRESQL"},"storage":{"type":"local","path":"/d","freeBytes":1,"totalBytes":2}}"""
        FakeServer { FakeServer.Reply(200, health) }.use { suwayomi ->
            preferences.useSuwayomi().set(true)
            val text = api(server(suwayomi.url)).check()
            assertTrue(text.contains("v2.4.9") && text.contains("POSTGRESQL"), text)
            assertEquals("/api/v1/karasu/health", suwayomi.requests.single().path)
        }
    }
}
