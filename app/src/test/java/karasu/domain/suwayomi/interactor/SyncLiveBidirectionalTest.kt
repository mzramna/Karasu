package karasu.domain.suwayomi.interactor

import karasu.data.suwayomi.RemoteManga
import karasu.data.suwayomi.ServerSyncApi
import karasu.domain.suwayomi.models.DownloadPolicy
import karasu.domain.suwayomi.models.HistoryEntry
import karasu.domain.suwayomi.models.MangaRef
import karasu.domain.suwayomi.models.MergedSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What the app and the server agree on, in both directions, against a real Suwayomi-Server (the
 * mzramna fork) on whichever database it runs on (H2 or PostgreSQL).
 *
 * Every test follows the same shape: a [LiveDevice] (the app's side, with the real [SyncSuwayomiServer])
 * and a [Web] (what the WebUI or another client does to the server), change one, sync, look at the other.
 *
 * Skipped unless `KARASU_LIVE_URL` is set; `scripts/live-sync-test.sh` starts a seeded server and sets it.
 * Each test owns one of the seeded works (`/live/mNN`), because the server's state is shared and permanent.
 */
class SyncLiveBidirectionalTest {

    @BeforeEach
    fun onlyWithAServer() = assumeTrue(Live.isConfigured, "KARASU_LIVE_URL not set: live server tests skipped")

    /** The server as another client sees it: the WebUI, or any app that talks to its GraphQL and REST API. */
    private class Web(val api: ServerSyncApi = Live.api()) {
        fun manga(n: Int): RemoteManga {
            var found: RemoteManga? = null
            runBlocking {
                api.forEachLibraryPage { page ->
                    page.firstOrNull { it.url == Live.mangaUrl(n) }?.let { found = it }
                }
            }
            return found ?: error("work $n is not in the server's library")
        }

        fun chapterId(n: Int, k: Int): Int = manga(n).chapters.first { it.url == Live.chapterUrl(n, k) }.id

        fun categoriesOf(n: Int): Set<String> {
            val byId = runBlocking { api.categories() }.associate { it.id to it.name }
            return manga(n).categoryIds.mapNotNullTo(mutableSetOf()) { byId[it] }
        }

        fun categoryNames(): Set<String> = runBlocking { api.categories() }.mapTo(mutableSetOf()) { it.name }

        fun createCategory(name: String) = runBlocking { api.createCategory(name) }

        fun link(n: Int, categoryName: String) {
            val id = runBlocking { api.categories() }.first { it.name == categoryName }.id
            runBlocking { api.updateMangaCategories(manga(n).id, listOf(id), emptyList()) }
        }

        fun unlink(n: Int, categoryName: String) {
            val id = runBlocking { api.categories() }.first { it.name == categoryName }.id
            runBlocking { api.updateMangaCategories(manga(n).id, emptyList(), listOf(id)) }
        }

        fun deleteCategory(name: String) {
            val id = runBlocking { api.categories() }.first { it.name == name }.id
            runBlocking {
                api.graphql("mutation { deleteCategory(input: {categoryId: $id}) { category { id } } }")
            }
        }

        fun chapter(n: Int, k: Int) = manga(n).chapters.first { it.url == Live.chapterUrl(n, k) }

        fun read(n: Int, k: Int, page: Int, read: Boolean = true) = runBlocking {
            api.updateChapters(listOf(ServerSyncApi.ChapterPatch(chapterId(n, k), read, page)))
        }

        /** Seconds, as the server keeps it. */
        fun setHistory(n: Int, k: Int, seconds: Long) {
            runBlocking { api.putHistory(listOf(HistoryEntry(chapterId(n, k).toLong(), seconds))) }
        }

        fun lastReadAt(n: Int, k: Int): Long = chapter(n, k).lastReadAt

        fun merged(n: Int) = runBlocking { api.mergedGroup(manga(n).id.toLong()) }.sources

        fun putMerged(n: Int, vararg sources: MergedSource) = runBlocking {
            api.putMerged(manga(n).id.toLong(), sources.toList())
        }

        fun deletions(after: Long = 0) = runBlocking { api.deletions(after) }

        fun cursor(): Long = deletions().maxOfOrNull { it.id } ?: 0L

        fun deleteChapterOnDevices(n: Int, k: Int) = runBlocking {
            api.requestDeletions(listOf(chapterId(n, k).toLong()), deleteFromServer = false)
        }

        fun downloadedOnServer(n: Int, k: Int): Boolean = runBlocking {
            api.lookupChapter(Live.SOURCE_ID, Live.mangaUrl(n), Live.chapterUrl(n, k))!!.downloaded
        }
    }

    private val web = lazy { Web() }
    private val server get() = web.value

    private fun unique(prefix: String) = "$prefix-${System.nanoTime()}"

    private fun device(label: String, vararg works: Int) = LiveDevice(label).also { d -> works.forEach { d.addManga(it) } }

    // =========================================================================== categories

    @Test
    fun `a category created on the app, with a work in it, shows up on the server`() {
        val app = device("phone", 1)
        val name = unique("from-app")
        app.createCategory(name)
        app.setCategories(1, name)

        val summary = app.sync()

        assertTrue(name in server.categoryNames(), "the category exists on the server")
        assertEquals(setOf(name), server.categoriesOf(1))
        assertTrue(summary.categoriesCreatedRemote >= 1)
        assertTrue(summary.categoryLinksRemote >= 1)
    }

    @Test
    fun `a category created on the server, with a work in it, shows up on the app`() {
        val app = device("phone", 2)
        val name = unique("from-server")
        server.createCategory(name)
        server.link(2, name)

        val summary = app.sync()

        assertTrue(name in app.categoryNames(), "the category exists on the app")
        assertEquals(setOf(name), app.categoriesOf(2))
        assertTrue(summary.categoriesCreatedLocal >= 1)
        assertTrue(summary.categoryLinksLocal >= 1)
    }

    @Test
    fun `moving a work to another category travels both ways`() {
        val app = device("phone", 3)
        val x = unique("x")
        val y = unique("y")
        app.createCategory(x)
        app.createCategory(y)
        app.setCategories(3, x)
        app.sync()
        assertEquals(setOf(x), server.categoriesOf(3))

        // app -> server: X becomes Y
        app.setCategories(3, y)
        app.sync()
        assertEquals(setOf(y), server.categoriesOf(3), "the server moved it, it did not keep both")

        // server -> app: Y becomes X
        server.unlink(3, y)
        server.link(3, x)
        app.sync()
        assertEquals(setOf(x), app.categoriesOf(3), "the app moved it, it did not keep both")
    }

    @Test
    fun `taking a work out of a category travels both ways`() {
        val app = device("phone", 4)
        val keep = unique("keep")
        val drop = unique("drop")
        app.createCategory(keep)
        app.createCategory(drop)
        app.setCategories(4, keep, drop)
        app.sync()
        assertEquals(setOf(keep, drop), server.categoriesOf(4))

        // app -> server
        app.setCategories(4, keep)
        app.sync()
        assertEquals(setOf(keep), server.categoriesOf(4))

        // server -> app
        server.link(4, drop)
        app.sync()
        assertEquals(setOf(keep, drop), app.categoriesOf(4), "added on the server")
        server.unlink(4, drop)
        app.sync()
        assertEquals(setOf(keep), app.categoriesOf(4), "removed on the server")
    }

    @Test
    fun `deleting a category on one side never deletes it on the other`() {
        val app = device("phone", 5)
        val fromApp = unique("gone-app")
        val fromServer = unique("gone-server")
        app.createCategory(fromApp)
        app.setCategories(5, fromApp)
        server.createCategory(fromServer)
        app.sync()
        assertTrue(fromApp in server.categoryNames())
        assertTrue(fromServer in app.categoryNames())

        // deleted on the app: the server keeps it, and the app gets it back
        app.deleteCategory(fromApp)
        app.sync()
        assertTrue(fromApp in server.categoryNames(), "still on the server")
        assertTrue(fromApp in app.categoryNames(), "and back on the app")

        // deleted on the server: the app keeps it, and the server gets it back
        server.deleteCategory(fromServer)
        assertFalse(fromServer in server.categoryNames())
        app.sync()
        assertTrue(fromServer in app.categoryNames(), "still on the app")
        assertTrue(fromServer in server.categoryNames(), "and back on the server")
    }

    // =========================================================================== reading progress

    @Test
    fun `reading a chapter travels both ways, page included`() {
        val app = device("phone", 6)
        app.read(6, 1, page = 2)

        val summary = app.sync()
        assertTrue(summary.chaptersPushed >= 1)
        assertEquals(true, server.chapter(6, 1).isRead)
        assertEquals(2, server.chapter(6, 1).lastPageRead)

        server.read(6, 2, page = 1)
        val pulled = app.sync()
        assertTrue(pulled.chaptersPulled >= 1)
        assertTrue(app.chapter(6, 2).read)
        assertEquals(1, app.chapter(6, 2).last_page_read)
    }

    @Test
    fun `progress only moves forward, and marking unread is not carried over`() {
        val app = device("phone", 7)
        app.read(7, 1, page = 2)
        app.sync()

        // the server drops back to page 1: the farther page wins and is written back
        server.read(7, 1, page = 1, read = true)
        app.sync()
        assertEquals(2, app.chapter(7, 1).last_page_read, "the app keeps the farther page")
        assertEquals(2, server.chapter(7, 1).lastPageRead, "and the server is brought forward again")

        // marking unread on the app does not undo the read state on the server...
        app.read(7, 1, page = 2, read = false)
        app.sync()
        assertEquals(true, server.chapter(7, 1).isRead)
        // ...and the read state on the server is not lost on the app either
        assertTrue(app.chapter(7, 1).read)
    }

    // =========================================================================== history

    @Test
    fun `what was read when travels both ways`() {
        val app = device("phone", 8)
        val appTime = 1_700_000_000_000L
        app.readAt(8, 1, appTime)
        val pushed = app.sync()
        assertEquals(appTime / 1000, server.lastReadAt(8, 1), "the server has the real time, not the sync time")
        assertTrue(pushed.historyPushed >= 1)

        val serverTime = 1_700_000_500L
        server.setHistory(8, 2, serverTime)
        val pulled = app.sync()
        assertEquals(serverTime * 1000, app.lastRead(8, 2))
        assertTrue(pulled.historyPulled >= 1)
    }

    @Test
    fun `removing a chapter from the history on the app removes it on the server`() {
        val app = device("phone", 9)
        app.readAt(9, 1, 1_700_000_000_000L)
        app.readAt(9, 2, 1_700_000_100_000L)
        app.sync()
        assertEquals(1_700_000_000L, server.lastReadAt(9, 1))

        app.removeFromHistory(9, 1)
        val summary = app.sync()

        assertEquals(0L, server.lastReadAt(9, 1), "removed on the server")
        assertEquals(1_700_000_100L, server.lastReadAt(9, 2), "the other chapter is left alone")
        assertEquals(0L, app.lastRead(9, 1))
        assertTrue(summary.historyPushed >= 1)
    }

    @Test
    fun `removing a chapter from the history on the server removes it on the app`() {
        val app = device("phone", 10)
        app.readAt(10, 1, 1_700_000_000_000L)
        app.readAt(10, 2, 1_700_000_100_000L)
        app.sync()

        server.setHistory(10, 1, 0)
        val summary = app.sync()

        assertEquals(0L, app.lastRead(10, 1), "removed on the app")
        assertEquals(1_700_000_100_000L, app.lastRead(10, 2), "the other chapter is left alone")
        assertEquals(0L, server.lastReadAt(10, 1))
        assertTrue(summary.historyPulled >= 1)
    }

    @Test
    fun `a removal never beats a read that happened on the other side`() {
        val app = device("phone", 11)
        app.readAt(11, 1, 1_700_000_000_000L)
        app.sync()

        // removed on the app, read again on the server in the meantime
        app.removeFromHistory(11, 1)
        server.setHistory(11, 1, 1_700_000_900L)
        app.sync()

        assertEquals(1_700_000_900_000L, app.lastRead(11, 1), "the newer read wins on the app")
        assertEquals(1_700_000_900L, server.lastReadAt(11, 1), "and stays on the server")
    }

    @Test
    fun `reading progress pushed to the server keeps the real read time instead of the sync time`() {
        val app = device("phone", 12)
        val longAgo = 1_600_000_000_000L
        app.read(12, 1, page = 2)
        app.readAt(12, 1, longAgo)

        app.sync()

        assertEquals(true, server.chapter(12, 1).isRead, "the progress went")
        assertEquals(longAgo / 1000, server.lastReadAt(12, 1), "and the server did not stamp it with now")
    }

    @Test
    fun `a chapter read again after being removed on the same side is back in the history`() {
        val app = device("phone", 13)
        app.readAt(13, 1, 1_700_000_000_000L)
        app.sync()
        app.removeFromHistory(13, 1)
        app.sync()
        assertEquals(0L, server.lastReadAt(13, 1))

        app.readAt(13, 1, 1_700_000_800_000L)
        app.sync()

        assertEquals(1_700_000_800L, server.lastReadAt(13, 1))
    }

    // =========================================================================== merged sources

    @Test
    fun `adding a merged source travels both ways`() {
        val app = device("phone", 14)
        runBlocking { app.addMerged(14, 7101, "/from/app") }
        val pushed = app.sync()
        assertEquals(listOf("7101"), server.merged(14).map { it.sourceId })
        assertEquals(1, pushed.mergedPushed)

        server.putMerged(
            14,
            MergedSource("7101", "/from/app", 0, true),
            MergedSource("7102", "/from/server", 1, true),
        )
        val pulled = app.sync()
        assertEquals(1, pulled.mergedPulled)
        assertEquals(listOf(7101L, 7102L), runBlocking { app.merged(14) }.map { it.source })
        assertEquals("/from/server", runBlocking { app.merged(14) }.last().url)
    }

    @Test
    fun `removing a merged source travels both ways`() {
        val app = device("phone", 15)
        runBlocking {
            app.addMerged(15, 7201, "/a")
            app.addMerged(15, 7202, "/b")
            app.addMerged(15, 7203, "/c")
        }
        app.sync()
        assertEquals(listOf("7201", "7202", "7203"), server.merged(15).map { it.sourceId })

        // app -> server
        runBlocking { app.removeMerged(15, 7202) }
        app.sync()
        assertEquals(listOf("7201", "7203"), server.merged(15).map { it.sourceId }, "gone on the server")

        // server -> app
        server.putMerged(15, MergedSource("7203", "/c", 0, true))
        app.sync()
        assertEquals(listOf(7203L), runBlocking { app.merged(15) }.map { it.source }, "gone on the app")
    }

    @Test
    fun `switching a merged source to reserve and back, and reordering, travel both ways`() {
        val app = device("phone", 16)
        runBlocking {
            app.addMerged(16, 7301, "/a")
            app.addMerged(16, 7302, "/b")
        }
        app.sync()

        // reserve: app -> server
        runBlocking { app.reserve(16, 7301, true) }
        app.sync()
        assertEquals(listOf(false, true), server.merged(16).map { it.updatesEnabled })

        // reserve: server -> app, and back
        server.putMerged(16, MergedSource("7301", "/a", 0, true), MergedSource("7302", "/b", 1, false))
        app.sync()
        assertEquals(listOf(true, false), runBlocking { app.merged(16) }.map { it.updatesEnabled })

        // order: app -> server
        runBlocking { app.reorderMerged(16, 7302, 7301) }
        app.sync()
        assertEquals(listOf("7302", "7301"), server.merged(16).map { it.sourceId })

        // order: server -> app
        server.putMerged(16, MergedSource("7301", "/a", 0, true), MergedSource("7302", "/b", 1, false))
        app.sync()
        assertEquals(listOf(7301L, 7302L), runBlocking { app.merged(16) }.map { it.source })
    }

    @Test
    fun `two devices adding different merged sources end up with both`() {
        val phone = device("phone", 17)
        val tablet = device("tablet", 17)
        runBlocking { phone.addMerged(17, 7401, "/phone") }
        runBlocking { tablet.addMerged(17, 7402, "/tablet") }

        phone.sync()
        tablet.sync()
        phone.sync()

        val onServer = server.merged(17).map { it.sourceId }.toSet()
        assertEquals(setOf("7401", "7402"), onServer)
        assertEquals(setOf(7401L, 7402L), runBlocking { phone.merged(17) }.map { it.source }.toSet())
        assertEquals(setOf(7401L, 7402L), runBlocking { tablet.merged(17) }.map { it.source }.toSet())
    }

    // =========================================================================== downloads and deletions

    @Test
    fun `a chapter deleted from the server's side is deleted from the app`() {
        val app = device("phone", 18)
        app.download(18, 1)
        app.download(18, 2)
        app.sync() // the first run only learns where the log ends

        server.deleteChapterOnDevices(18, 1)
        val summary = app.sync()

        assertFalse(app.isDownloaded(18, 1), "the chapter the server was told to drop is gone")
        assertTrue(app.isDownloaded(18, 2), "the other one is not touched")
        assertEquals(1, summary.deletionsApplied)
        assertTrue(server.downloadedOnServer(18, 1), "devices were asked, the server's own copy stays")
    }

    @Test
    fun `a chapter deleted on the app is announced to the server and its copy stays`() {
        val app = device("phone", 19)
        app.download(19, 1)
        app.download(19, 2)
        app.sync()
        val before = server.cursor()

        app.deleteDownload(19, 1)
        val summary = app.sync()

        assertEquals(1, summary.deletionsPushed)
        val announced = server.deletions(before)
        assertEquals(1, announced.size)
        assertEquals(Live.mangaUrl(19), announced.single().mangaUrl)
        assertEquals(Live.chapterUrl(19, 1), announced.single().chapterUrl)
        assertTrue(server.downloadedOnServer(19, 1), "announcing is not deleting the server's copy")
    }

    @Test
    fun `a deletion on one device reaches the other through the server, and is not sent back`() {
        val phone = device("phone", 20)
        val tablet = device("tablet", 20)
        listOf(phone, tablet).forEach { it.download(20, 1); it.download(20, 2); it.sync() }

        phone.deleteDownload(20, 1)
        phone.sync()
        val afterPhone = server.cursor()

        val summary = tablet.sync()
        assertFalse(tablet.isDownloaded(20, 1), "the tablet dropped it")
        assertTrue(tablet.isDownloaded(20, 2))
        assertEquals(1, summary.deletionsApplied)
        assertEquals(0, summary.deletionsPushed, "what the tablet did because of the server is not announced again")
        assertEquals(afterPhone, server.cursor(), "so the log did not grow")
    }

    @Test
    fun `an announcement is not echoed, and a chapter downloaded again is not deleted by it`() {
        val app = device("phone", 21)
        app.download(21, 1)
        app.sync()
        app.deleteDownload(21, 1)
        app.sync()
        val settled = server.cursor()

        // nothing changed: nothing is announced and nothing is applied
        val idle = app.sync()
        assertEquals(0, idle.deletionsPushed)
        assertEquals(0, idle.deletionsApplied)
        assertEquals(settled, server.cursor())

        // downloaded again later: its own old announcement must not delete it
        app.download(21, 1)
        val again = app.sync()
        assertTrue(app.isDownloaded(21, 1), "downloaded again, and still there after a sync")
        assertEquals(0, again.deletionsApplied)
    }

    @Test
    fun `a device that is new to the server does not replay its history of deletions`() {
        val old = device("old", 22)
        old.download(22, 1)
        old.sync()
        server.deleteChapterOnDevices(22, 1) // something from "last year"

        val fresh = device("fresh", 22)
        fresh.download(22, 1)
        val summary = fresh.sync()

        assertTrue(fresh.isDownloaded(22, 1), "what the user has today is not deleted for something old")
        assertEquals(0, summary.deletionsApplied)
        // but from now on it follows the log
        server.deleteChapterOnDevices(22, 2)
        fresh.download(22, 2)
        assertEquals(1, fresh.sync().deletionsApplied)
        assertFalse(fresh.isDownloaded(22, 2))
    }

    @Test
    fun `deleting is shared only when the user asked for it`() {
        val app = device("phone", 23)
        app.preferences.pushLocalDeletions().set(false)
        app.download(23, 1)
        app.sync()
        val before = server.cursor()

        app.deleteDownload(23, 1)
        val summary = app.sync()

        assertEquals(0, summary.deletionsPushed)
        assertEquals(before, server.cursor(), "nothing was announced")
    }

    // =========================================================================== backups

    @Test
    fun `a backup sent by one device is there for another one, byte for byte, and can be removed`() = runBlocking {
        val phone = Live.api()
        val tablet = Live.api()
        val payload = ByteArray(8_192) { (it * 7 + 3).toByte() }
        val sent = phone.uploadBackup("sync-test.tachibk", "Phone", payload.size.toLong()) { payload.inputStream() }
        try {
            // the other device sees it and who sent it
            val listed = tablet.listBackups().single { it.id == sent.id }
            assertEquals("Phone", listed.device)
            assertEquals(payload.size.toLong(), listed.sizeBytes)

            // and restores from it
            val target = File.createTempFile("restore", ".tachibk")
            try {
                tablet.downloadBackup(sent.id, target)
                assertArrayEquals(payload, target.readBytes())
            } finally {
                target.delete()
            }

            // the other direction: a backup the server has (put there by another client) reaches the app too
            val fromTablet = tablet.uploadBackup("from-tablet.tachibk", "Tablet", 4) { byteArrayOf(1, 2, 3, 4).inputStream() }
            try {
                assertTrue(phone.listBackups().any { it.id == fromTablet.id && it.device == "Tablet" })
            } finally {
                phone.deleteBackup(fromTablet.id)
            }
        } finally {
            tablet.deleteBackup(sent.id)
        }
        assertTrue(phone.listBackups().none { it.id == sent.id }, "removed for everyone")
    }

    // =========================================================================== policy

    @Test
    fun `the download policy set on the server reaches the app, whichever way it is set`() {
        val app = device("phone", 24)
        val original = runBlocking { Live.api().downloadPolicy() }
        try {
            runBlocking { Live.api().setDownloadPolicy(DownloadPolicy.SERVER_AND_CLIENTS) }
            app.sync()
            assertEquals("SERVER_AND_CLIENTS", app.preferences.serverDownloadPolicy().get())

            runBlocking { Live.api().setDownloadPolicy(DownloadPolicy.SERVER_ONLY) }
            app.sync()
            assertEquals("SERVER_ONLY", app.preferences.serverDownloadPolicy().get())
        } finally {
            runBlocking { Live.api().setDownloadPolicy(original) }
        }
    }

    // =========================================================================== everything at once

    @Test
    fun `two devices and the server converge on categories, progress, history and merges, and then stay quiet`() {
        val phone = device("phone", 25)
        val tablet = device("tablet", 25)
        val cat = unique("shared")

        // the phone does a bit of everything
        phone.createCategory(cat)
        phone.setCategories(25, cat)
        phone.read(25, 1, page = 2)
        phone.readAt(25, 1, 1_700_000_000_000L)
        runBlocking { phone.addMerged(25, 7501, "/phone-extra") }
        phone.sync()

        // the tablet gets all of it
        tablet.sync()
        assertEquals(setOf(cat), tablet.categoriesOf(25))
        assertTrue(tablet.chapter(25, 1).read)
        assertEquals(2, tablet.chapter(25, 1).last_page_read)
        assertEquals(1_700_000_000_000L, tablet.lastRead(25, 1))
        assertEquals(listOf(7501L), runBlocking { tablet.merged(25) }.map { it.source })

        // and does its own thing, including removing what the phone had put in the history
        tablet.read(25, 2, page = 1)
        tablet.removeFromHistory(25, 1)
        tablet.setCategories(25)
        runBlocking { tablet.addMerged(25, 7502, "/tablet-extra") }
        tablet.sync()

        // the phone follows
        phone.sync()
        assertTrue(phone.chapter(25, 2).read)
        assertEquals(0L, phone.lastRead(25, 1), "history removal made on the tablet")
        assertEquals(emptySet<String>(), phone.categoriesOf(25), "category link removed on the tablet")
        assertEquals(setOf(7501L, 7502L), runBlocking { phone.merged(25) }.map { it.source }.toSet())

        // the server agrees with both
        assertEquals(emptySet<String>(), server.categoriesOf(25))
        assertEquals(0L, server.lastReadAt(25, 1))
        assertEquals(setOf("7501", "7502"), server.merged(25).map { it.sourceId }.toSet())

        // and with nothing left to say, a sync changes nothing
        val quietPhone = phone.sync()
        val quietTablet = tablet.sync()
        val quietPhoneAgain = phone.sync()
        listOf(quietPhone, quietTablet, quietPhoneAgain).forEach {
            assertTrue(it.isIdle, "nothing to do, yet: $it")
            assertTrue(it.errors.isEmpty(), "no errors: ${it.errors}")
        }
    }
}
