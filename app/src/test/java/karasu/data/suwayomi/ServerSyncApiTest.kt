package karasu.data.suwayomi

import karasu.domain.suwayomi.models.DownloadPolicy
import karasu.domain.suwayomi.models.MangaRef
import karasu.domain.suwayomi.models.MergedSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerSyncApiTest {

    private fun api(server: FakeServer, secondary: String = "", on: Boolean = true): ServerSyncApi {
        val reachability = ServerReachability()
        return ServerSyncApi(
            transport = ServerTransport(
                config = { ServerConfig(server.url, secondary, "", "") },
                baseClient = OkHttpClient(),
                reachability = reachability,
            ),
            reachability = reachability,
            isSwitchedOn = { on },
        )
    }

    private fun reply(body: String, code: Int = 200) = FakeServer.Reply(code, body)

    @Test
    fun `health reports what the server said and how long it took`() = runBlocking {
        val body = """
            {"status":"ok","serverVersion":"v2.1.0","apiVersion":1,
             "database":{"type":"POSTGRESQL"},
             "storage":{"type":"nfs","path":"/data","freeBytes":10,"totalBytes":100},"extra":true}
        """.trimIndent()
        FakeServer { reply(body) }.use { server ->
            val result = api(server).health()
            assertEquals("POSTGRESQL", result.health.database.type)
            assertEquals("nfs", result.health.storage.type)
            assertEquals(ServerOrigin.PRIMARY, result.origin)
            assertTrue(result.latencyMs >= 0)
            assertEquals("/api/v1/karasu/health", server.requests.single().path)
        }
    }

    @Test
    fun `a chapter lookup that finds nothing is null, not an error`() = runBlocking {
        FakeServer { reply("""{"error":"not found"}""", 404) }.use { server ->
            assertNull(api(server).lookupChapter(1L, "/m", "/c"))
        }
    }

    @Test
    fun `a chapter lookup sends every identifying field`() = runBlocking {
        FakeServer { reply("""{"chapterId":7,"mangaId":2,"downloaded":true,"sizeBytes":123}""") }.use { server ->
            val found = api(server).lookupChapter(9223372036854775807L, "/manga/x y", "/ch?1")!!
            assertTrue(found.downloaded)
            assertEquals(7L, found.chapterId)
            val query = server.requests.single().query!!
            assertTrue(query.contains("sourceId=9223372036854775807"))
            assertTrue(query.contains("mangaUrl=%2Fmanga%2Fx%20y") || query.contains("mangaUrl=/manga/x%20y"))
            assertTrue(query.contains("chapterUrl="))
        }
    }

    @Test
    fun `an archive that is not downloaded gives null and a stored one is streamed`() = runBlocking {
        var downloaded = false
        FakeServer { if (downloaded) reply("PK-bytes") else reply("{}", 404) }.use { server ->
            val api = api(server)
            assertNull(api.openChapterArchive(5) { _, _ -> "never" })
            downloaded = true
            val text = api.openChapterArchive(5) { stream, length -> stream.readBytes().decodeToString() + ":$length" }
            assertEquals("PK-bytes:8", text)
        }
    }

    @Test
    fun `an error status becomes an exception carrying the server's message`() {
        FakeServer { reply("""{"error":"database is locked"}""", 503) }.use { server ->
            val e = assertThrows(ServerApiException::class.java) { runBlocking { api(server).listBackups() } }
            assertEquals(503, e.code)
            assertTrue(e.message!!.contains("database is locked"))
        }
    }

    @Test
    fun `putMerged by primary sends string ids and treats 404 as nothing to attach to`() = runBlocking {
        FakeServer { reply("""{"error":"no such work"}""", 404) }.use { server ->
            val group = api(server).putMerged(
                MangaRef("9223372036854775807", "/m"),
                listOf(MergedSource("123", "/x", priority = 0)),
            )
            assertNull(group)
            val request = server.requests.single()
            assertEquals("PUT", request.method)
            assertEquals("/api/v1/karasu/merged", request.path)
            assertTrue(request.body.contains(""""sourceId":"9223372036854775807""""))
            assertTrue(request.body.contains(""""updatesEnabled":true"""))
        }
    }

    @Test
    fun `a merged group accepts a numeric source id from a careless server`() = runBlocking {
        val body = """[{"mangaId":1,"primary":{"sourceId":42,"url":"/m"},"sources":[{"sourceId":"7","url":"/x","priority":1}]}]"""
        FakeServer { reply(body) }.use { server ->
            val group = api(server).mergedGroups().single()
            assertEquals("42", group.primary.sourceId)
            assertEquals("7", group.sources.single().sourceId)
            assertTrue(group.sources.single().updatesEnabled)
        }
    }

    @Test
    fun `policy and deletions are parsed`() = runBlocking {
        FakeServer { request ->
            if (request.path.endsWith("/policy")) {
                reply("""{"downloadPolicy":"SERVER_ONLY"}""")
            } else {
                reply("""[{"id":5,"sourceId":"1","mangaUrl":"/m","chapterUrl":"/c","chapterNumber":3.0,"requestedAt":1}]""")
            }
        }.use { server ->
            val api = api(server)
            assertEquals(DownloadPolicy.SERVER_ONLY, api.downloadPolicy())
            val deletion = api.deletions(4).single()
            assertEquals(5L, deletion.id)
            assertEquals("afterId=4", server.requests.last().query)
        }
    }

    @Test
    fun `an unknown policy is the permissive one`() {
        assertEquals(DownloadPolicy.SERVER_AND_CLIENTS, DownloadPolicy.parse("SOMETHING_NEW"))
        assertEquals(DownloadPolicy.SERVER_AND_CLIENTS, DownloadPolicy.parse(null))
    }

    @Test
    fun `graphql errors are raised even on an HTTP 200`() {
        FakeServer { reply("""{"data":null,"errors":[{"message":"nope"}]}""") }.use { server ->
            val e = assertThrows(ServerApiException::class.java) { runBlocking { api(server).categories() } }
            assertTrue(e.message!!.contains("nope"))
            assertEquals("/api/graphql", server.requests.single().path)
        }
    }

    @Test
    fun `the library walk follows pages and decodes categories and chapters`() = runBlocking {
        var page = 0
        FakeServer {
            page++
            val next = page == 1
            reply(
                """{"data":{"mangas":{"nodes":[{"id":$page,"sourceId":"3","url":"/m$page",
                   "categories":{"nodes":[{"id":4}]},
                   "chapters":{"nodes":[{"id":9,"url":"/c","isRead":true,"lastPageRead":12}]}}],
                   "pageInfo":{"hasNextPage":$next}}}}""",
            )
        }.use { server ->
            val seen = mutableListOf<RemoteManga>()
            api(server).forEachLibraryPage { seen += it }
            assertEquals(listOf("/m1", "/m2"), seen.map { it.url })
            assertEquals(setOf(4), seen.first().categoryIds)
            assertEquals(12, seen.first().chapters.single().lastPageRead)
            assertTrue(server.requests[1].body.contains(""""offset":1"""))
        }
    }

    @Test
    fun `chapter updates go out in one aliased document per batch`() = runBlocking {
        FakeServer { reply("""{"data":{}}""") }.use { server ->
            api(server).updateChapters(
                listOf(
                    ServerSyncApi.ChapterPatch(10, true, 3),
                    ServerSyncApi.ChapterPatch(11, false, 8),
                ),
            )
            val body = server.requests.single().body
            assertTrue(body.contains("c0: updateChapter"))
            assertTrue(body.contains("c1: updateChapter"))
            assertTrue(body.contains(""""id1":11"""))
            assertTrue(body.contains(""""lastPageRead":8"""))
        }
    }

    @Test
    fun `the update document is valid for any batch size`() {
        val document = ServerGraphQl.updateChaptersDocument(2)
        assertTrue(document.startsWith("mutation KarasuUpdateChapters(\$id0: Int!, \$patch0: UpdateChapterPatchInput!, \$id1: Int!, \$patch1: UpdateChapterPatchInput!)"))
        assertEquals(2, Regex("updateChapter\\(").findAll(document).count())
    }

    @Test
    fun `background work needs both the switch and an address`() {
        FakeServer { reply("{}") }.use { server ->
            assertTrue(api(server).isEnabled)
            assertFalse(api(server, on = false).isEnabled)
            assertTrue(api(server, on = false).isConfigured)
        }
    }

    @Test
    fun `a backup name is sent as one path segment`() = runBlocking {
        FakeServer { reply("", 204) }.use { server ->
            api(server).deleteBackup("old backup/1.tachibk")
            val request = server.requests.single()
            assertEquals("DELETE", request.method)
            assertEquals("/api/v1/karasu/backups/old%20backup%2F1.tachibk", request.path)
        }
    }

    @Test
    fun `an upload is multipart with the device in the query`() = runBlocking {
        FakeServer {
            reply("""{"id":"b.tachibk","name":"b.tachibk","sizeBytes":3,"createdAt":1,"device":"Pixel 8"}""", 201)
        }.use { server ->
            val info = api(server).uploadBackup("b.tachibk", "Pixel 8", 3) { "abc".byteInputStream() }
            assertEquals("Pixel 8", info.device)
            val request = server.requests.single()
            assertEquals("device=Pixel%208", request.query)
            assertTrue(request.headers["content-type"]!!.startsWith("multipart/form-data"))
            assertTrue(request.body.contains("""name="file"; filename="b.tachibk""""))
            assertTrue(request.body.contains("abc"))
        }
    }
}
