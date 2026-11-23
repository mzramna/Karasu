package karasu.domain.suwayomi.interactor

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A server from before the operation log existed (its `/operations` routes are a 404). Only the mock can be
 * told to behave that way. The sync must work as it did, with the older rules settling conflicts.
 */
class SyncOldServerTest {

    private lateinit var old: MockSuwayomiServer

    @BeforeEach
    fun start() {
        old = MockSuwayomiServer().also {
            it.operationLogEnabled = false
            it.start()
        }
    }

    @AfterEach
    fun stop() = old.stop()

    private fun device(vararg works: Int): LiveDevice {
        val id = "device-old-${System.nanoTime()}"
        return LiveDevice("old", id, Live.api(primary = old.url, deviceId = id)).also { d -> works.forEach { d.addManga(it) } }
    }

    private val web get() = Live.api(primary = old.url)

    @Test
    fun `it syncs without errors and the progress arrives`() {
        val app = device(1)
        app.read(1, 1, page = 2)

        val summary = app.sync()

        assertTrue(summary.errors.isEmpty(), "no log is not an error: ${summary.errors}")
        assertTrue(app.operations.all().isNotEmpty(), "the device still keeps its own log")
        val server = kotlinx.coroutines.runBlocking {
            var found: karasu.data.suwayomi.RemoteManga? = null
            web.forEachLibraryPage { page -> page.firstOrNull { it.url == Live.mangaUrl(1) }?.let { found = it } }
            found!!
        }
        assertEquals(true, server.chapters.first { it.url == Live.chapterUrl(1, 1) }.isRead)
    }

    @Test
    fun `a conflict is settled by the older rule, read further wins over an unmarking`() {
        val app = device(2)
        app.read(2, 1, page = 2)
        app.sync()

        // the same collision the log settles in favour of the later change: here nothing can say which was later
        app.at(+600_000) { app.read(2, 1, page = 0, read = false) }
        kotlinx.coroutines.runBlocking {
            web.updateChapters(listOf(karasu.data.suwayomi.ServerSyncApi.ChapterPatch(chapterIdOnServer(2, 1), true, 3)))
        }
        app.sync()

        assertTrue(app.chapter(2, 1).read, "reading made further is kept")
        assertEquals(3, app.chapter(2, 1).last_page_read)
    }

    @Test
    fun `a rename on both sides gives the server's name when no log can say which was later`() {
        val app = device()
        val old1 = "old-" + System.nanoTime()
        app.createCategory(old1)
        app.sync()
        val appName = "app-" + System.nanoTime()
        val webName = "web-" + System.nanoTime()
        app.at(+600_000) { app.renameCategory(old1, appName) }
        kotlinx.coroutines.runBlocking {
            val id = web.categories().first { it.name == old1 }.id
            web.renameCategory(id, webName)
        }

        app.sync()

        assertTrue(webName in app.categoryNames())
        assertFalse(appName in app.categoryNames())
    }

    private fun chapterIdOnServer(n: Int, k: Int): Int = kotlinx.coroutines.runBlocking {
        var id = 0
        web.forEachLibraryPage { page -> page.firstOrNull { it.url == Live.mangaUrl(n) }?.let { m -> id = m.chapters.first { it.url == Live.chapterUrl(n, k) }.id } }
        id
    }
}
