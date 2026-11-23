package karasu.data.suwayomi

import karasu.domain.suwayomi.models.DownloadPolicy
import karasu.domain.suwayomi.models.MangaRef
import karasu.domain.suwayomi.models.MergedSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Talks to a real Suwayomi-Server (the mzramna fork). Skipped unless the environment says where it is:
 *
 *   KARASU_LIVE_URL=http://127.0.0.1:4591 KARASU_LIVE_USER=karasu KARASU_LIVE_PASS=s3cret \
 *   KARASU_LIVE_MANGA_URL=/seed/alpha KARASU_LIVE_SOURCE_ID=0 \
 *   KARASU_LIVE_DOWNLOADED=/seed/alpha/c2 KARASU_LIVE_MISSING=/seed/alpha/c3 \
 *   ./gradlew :app:testStandardDebugUnitTest --tests '*ServerSyncApiLiveTest'
 *
 * The server needs a library manga with one chapter downloaded as CBZ and one that is not.
 */
class ServerSyncApiLiveTest {

    private val url = System.getenv("KARASU_LIVE_URL").orEmpty()
    private val user = System.getenv("KARASU_LIVE_USER").orEmpty()
    private val pass = System.getenv("KARASU_LIVE_PASS").orEmpty()
    private val sourceId = (System.getenv("KARASU_LIVE_SOURCE_ID") ?: "0").toLong()
    private val mangaUrl = System.getenv("KARASU_LIVE_MANGA_URL") ?: "/seed/alpha"
    private val downloadedUrl = System.getenv("KARASU_LIVE_DOWNLOADED") ?: "/seed/alpha/c2"
    private val missingUrl = System.getenv("KARASU_LIVE_MISSING") ?: "/seed/alpha/c3"

    private fun api(primary: String = url, secondary: String = ""): ServerSyncApi {
        assumeTrue(url.isNotEmpty(), "KARASU_LIVE_URL not set: live server test skipped")
        val reachability = ServerReachability()
        return ServerSyncApi(
            transport = ServerTransport(
                config = { ServerConfig(primary, secondary, user, pass) },
                baseClient = OkHttpClient(),
                reachability = reachability,
            ),
            reachability = reachability,
        )
    }

    @Test
    fun `health tells the database, the storage and which address answered`() = runBlocking {
        val result = api().health()
        println("LIVE health: ${result.health.serverVersion} db=${result.health.database.type} " +
            "storage=${result.health.storage.type} ${result.health.storage.freeBytes}/${result.health.storage.totalBytes} " +
            "origin=${result.origin} ${result.latencyMs}ms")
        assertEquals("ok", result.health.status)
        assertTrue(result.health.database.type in setOf("H2", "POSTGRESQL"))
        assertEquals(ServerOrigin.PRIMARY, result.origin)
    }

    @Test
    fun `a wrong password is refused instead of being taken for a server that is not the fork`() {
        assumeTrue(System.getenv("KARASU_LIVE_URL") != null)
        val reachability = ServerReachability()
        val bad = ServerSyncApi(
            ServerTransport({ ServerConfig(url, "", user, "wrong") }, OkHttpClient(), reachability),
            reachability,
        )
        // the answer is an HTTP error from the server, never a network failure
        val error = assertThrows(ServerApiException::class.java) { runBlocking { bad.health() } }
        println("LIVE wrong password -> HTTP ${error.code}")
        assertTrue(error.code in setOf(401, 403, 404))
    }

    @Test
    fun `an unreachable LAN address falls back to the second one`() = runBlocking {
        val result = api(primary = "http://127.0.0.1:1", secondary = url).health()
        println("LIVE failover -> origin=${result.origin} base=${result.baseUrl}")
        assertEquals(ServerOrigin.SECONDARY, result.origin)
        assertEquals("ok", result.health.status)
    }

    @Test
    fun `a downloaded chapter is found by source and urls and streamed as a valid CBZ`() = runBlocking {
        val api = api()
        val found = api.lookupChapter(sourceId, mangaUrl, downloadedUrl)
        assertNotNull(found)
        assertTrue(found!!.downloaded)

        val bytes = api.openChapterArchive(found.chapterId) { stream, length ->
            val data = stream.readBytes()
            assertEquals(length, data.size.toLong())
            data
        }
        assertNotNull(bytes)
        // a real zip with the pages in it
        val entries = ZipInputStream(bytes!!.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }
        println("LIVE archive: ${bytes.size} bytes, pages=$entries, size reported=${api.chapterArchiveSize(found.chapterId)}")
        assertTrue(entries.size >= 1)
        assertEquals(bytes.size.toLong(), api.chapterArchiveSize(found.chapterId))
    }

    @Test
    fun `a chapter the server does not have is null, not an error`() = runBlocking {
        val api = api()
        val missing = api.lookupChapter(sourceId, mangaUrl, missingUrl)
        assertNotNull(missing)
        assertFalse(missing!!.downloaded)
        assertNull(api.openChapterArchive(missing.chapterId) { _, _ -> error("must not be called") })
        assertNull(api.lookupChapter(sourceId, mangaUrl, "/does/not/exist"))
    }

    @Test
    fun `backups go up, show in the list, come back identical and can be deleted`() = runBlocking {
        val api = api()
        val payload = ByteArray(5_000) { (it * 31).toByte() }
        val sent = api.uploadBackup("live-test.tachibk", "Karasu live test", payload.size.toLong()) { payload.inputStream() }
        try {
            assertEquals("Karasu live test", sent.device)
            assertEquals(payload.size.toLong(), sent.sizeBytes)
            assertTrue(api.listBackups().any { it.id == sent.id })

            val target = File.createTempFile("live-backup", ".tachibk")
            try {
                api.downloadBackup(sent.id, target)
                assertArrayEquals(payload, target.readBytes())
            } finally {
                target.delete()
            }
        } finally {
            api.deleteBackup(sent.id)
        }
        assertFalse(api.listBackups().any { it.id == sent.id })
    }

    @Test
    fun `merged sources are written by source and url and read back`() = runBlocking {
        val api = api()
        val primary = MangaRef(sourceId.toString(), mangaUrl)
        val group = api.putMerged(
            primary,
            listOf(MergedSource("7001", "/a", priority = 0), MergedSource("7002", "/b", priority = 1, updatesEnabled = false)),
        )
        assertNotNull(group)
        assertEquals(listOf("7001", "7002"), group!!.sources.map { it.sourceId })
        assertEquals(listOf(true, false), group.sources.map { it.updatesEnabled })
        assertTrue(api.mergedGroups().any { it.mangaId == group.mangaId })
        assertEquals(2, api.mergedGroup(group.mangaId).sources.size)

        // a manga the server does not know is "skip it", not a failure
        assertNull(api.putMerged(MangaRef("0", "/never/seen"), listOf(MergedSource("7001", "/a"))))

        api.putMerged(primary, emptyList())
        assertTrue(api.mergedGroup(group.mangaId).sources.isEmpty())
    }

    @Test
    fun `the download policy round trips`() = runBlocking {
        val api = api()
        val before = api.downloadPolicy()
        try {
            val other = if (before == DownloadPolicy.SERVER_ONLY) DownloadPolicy.SERVER_AND_CLIENTS else DownloadPolicy.SERVER_ONLY
            assertEquals(other, api.setDownloadPolicy(other))
            assertEquals(other, api.downloadPolicy())
        } finally {
            api.setDownloadPolicy(before)
        }
    }

    @Test
    fun `remote deletions are queued by chapter and read back after a cursor`() = runBlocking {
        val api = api()
        val chapterId = api.lookupChapter(sourceId, mangaUrl, downloadedUrl)!!.chapterId
        val cursor = api.deletions(0).maxOfOrNull { it.id } ?: 0L
        assertEquals(1, api.requestDeletions(listOf(chapterId), deleteFromServer = false))
        val fresh = api.deletions(cursor)
        println("LIVE deletions after cursor $cursor: ${fresh.map { it.sourceId + ' ' + it.mangaUrl + ' ' + it.chapterUrl }}")
        assertEquals(1, fresh.size)
        assertEquals(sourceId.toString(), fresh.single().sourceId)
        assertEquals(mangaUrl, fresh.single().mangaUrl)
        assertEquals(downloadedUrl, fresh.single().chapterUrl)
        // and the file is still there, because only the devices were asked
        assertTrue(api.lookupChapter(sourceId, mangaUrl, downloadedUrl)!!.downloaded)
    }

    @Test
    fun `categories and reading progress go through the GraphQL API`() = runBlocking {
        val api = api()
        val name = "live-" + System.nanoTime()
        val created = api.createCategory(name)
        assertTrue(api.categories().any { it.id == created.id && it.name == name })

        var mine: karasu.data.suwayomi.RemoteManga? = null
        api.forEachLibraryPage { page ->
            page.firstOrNull { it.sourceId == sourceId.toString() && it.url == mangaUrl }?.let { mine = it }
        }
        assertNotNull(mine)
        val chapter = mine!!.chapters.first { it.url == downloadedUrl }

        api.updateMangaCategories(mine!!.id, listOf(created.id), emptyList())
        api.updateChapters(listOf(ServerSyncApi.ChapterPatch(chapter.id, isRead = true, lastPageRead = 2)))

        var again: karasu.data.suwayomi.RemoteManga? = null
        api.forEachLibraryPage { page ->
            page.firstOrNull { it.id == mine!!.id }?.let { again = it }
        }
        val readBack = again!!.chapters.first { it.id == chapter.id }
        println("LIVE graphql: category ${created.id} on manga=${created.id in again!!.categoryIds}, chapter read=${readBack.isRead} page=${readBack.lastPageRead}")
        assertTrue(created.id in again!!.categoryIds)
        assertTrue(readBack.isRead)
        assertEquals(2, readBack.lastPageRead)
    }
}
