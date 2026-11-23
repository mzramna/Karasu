package karasu.domain.suwayomi.interactor

import karasu.data.suwayomi.RemoteManga
import karasu.data.suwayomi.ServerSyncApi
import karasu.domain.suwayomi.models.DownloadPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The full-stack battery: the real server, the real WebUI clicked through in a real browser, and the app's sync
 * code, all talking to each other. Each scenario changes something on one side and looks at the other two.
 *
 * Run by `scripts/full-stack/run.sh`, never by the pipelines: they have no browser here and no reason to build all
 * three on every change (they use the mocks instead). Skipped unless that script set the stack up.
 */
class FullStackTest {

    private val web = WebUi()
    private val api: ServerSyncApi by lazy { Live.api() }

    @BeforeEach
    fun onlyWithTheStack() = assumeTrue(web.isAvailable, "KARASU_FULLSTACK_DRIVER not set: full-stack battery skipped")

    private fun unique(prefix: String) = "$prefix-${System.nanoTime() % 1_000_000}"

    private fun serverManga(n: Int): RemoteManga {
        var found: RemoteManga? = null
        runBlocking { api.forEachLibraryPage { page -> page.firstOrNull { it.url == Live.mangaUrl(n) }?.let { found = it } } }
        return found ?: error("work $n is not in the server's library")
    }

    private fun mangaId(n: Int) = serverManga(n).id.toString()

    private fun chapterOnServer(n: Int, k: Int) = serverManga(n).chapters.first { it.url == Live.chapterUrl(n, k) }

    private fun device(label: String, vararg works: Int) = LiveDevice(label).also { d -> works.forEach { d.addManga(it) } }

    private fun names(answer: kotlinx.serialization.json.JsonElement, field: String): List<String> =
        (answer.jsonObject[field] as JsonArray).map { it.jsonPrimitive.content }

    private fun webSeesRead(n: Int, k: Int): Boolean =
        web.run("chapter", mangaId(n), "Chapter $k").jsonObject["read"]!!.jsonPrimitive.boolean

    // =========================================================================== the stack itself

    @Test
    fun `the WebUI is served by the server and answers`() {
        assertTrue(web.run("ping").toString().contains("ok"))
        assertEquals("ok", runBlocking { api.health() }.health.status)
    }

    // =========================================================================== reading

    @Test
    fun `what the app read is what the WebUI shows, and an unread clicked in the WebUI reaches the app`() {
        val app = device("phone", 1)
        app.read(1, 1, page = 2)
        app.readAt(1, 1, 1_700_000_000_000L)
        app.sync()

        assertTrue(webSeesRead(1, 1), "the WebUI shows the chapter the app read as read")
        assertFalse(webSeesRead(1, 2), "and the other one as not read")
        val history = web.run("history").toString()
        assertTrue(history.contains("Live M01"), "the chapter is in the WebUI's history: $history")

        web.run("mark", mangaId(1), "Chapter 1", "unread")
        assertEquals(false, chapterOnServer(1, 1).isRead, "the click reached the server")
        app.sync()
        assertFalse(app.chapter(1, 1).read, "and the app followed it")
        app.sync()
        assertFalse(app.chapter(1, 1).read, "and nobody read it again")
    }

    @Test
    fun `a chapter marked read in the WebUI is read on the app, and the app's unread comes back to the WebUI`() {
        val app = device("phone", 2)
        app.sync()

        web.run("mark", mangaId(2), "Chapter 2", "read")
        app.sync()
        assertTrue(app.chapter(2, 2).read, "read in the WebUI, read on the app")

        app.read(2, 2, page = 0, read = false)
        app.sync()
        assertFalse(webSeesRead(2, 2), "the app's unread shows in the WebUI")
    }

    // =========================================================================== categories

    @Test
    fun `a category made and filled in the WebUI is on the app, renamed in either one it is renamed in both`() {
        val app = device("phone", 3)
        val first = unique("webcat")
        val second = unique("renamed-on-app")
        val third = unique("renamed-on-web")

        // created and filled by clicking in the WebUI
        web.run("create-category", first)
        web.run("set-manga-categories", mangaId(3), first)
        app.sync()
        assertTrue(first in app.categoryNames(), "the category reached the app")
        assertEquals(setOf(first), app.categoriesOf(3), "with the work in it")

        // renamed on the app: the WebUI shows the new name and not a second category
        app.renameCategory(first, second)
        app.sync()
        val afterAppRename = names(web.run("categories"), "names")
        assertTrue(second in afterAppRename && first !in afterAppRename, "the WebUI shows the app's name: $afterAppRename")
        assertEquals(listOf(second), names(web.run("manga-categories", mangaId(3)), "categories"), "the work follows the category")

        // renamed in the WebUI: the app follows, again without a second category
        web.run("rename-category", second, third)
        app.sync()
        assertTrue(third in app.categoryNames() && second !in app.categoryNames(), "the app shows the WebUI's name")
        assertEquals(setOf(third), app.categoriesOf(3))

        // moved out of the category in the WebUI: gone from the app
        web.run("set-manga-categories", mangaId(3), "")
        app.sync()
        assertEquals(emptySet<String>(), app.categoriesOf(3), "taken out in the WebUI, taken out on the app")
    }

    @Test
    fun `a category made on the app shows up in the WebUI with its work`() {
        val app = device("phone", 4)
        val name = unique("appcat")
        app.createCategory(name)
        app.setCategories(4, name)

        app.sync()

        assertTrue(name in names(web.run("categories"), "names"), "the WebUI lists the category")
        assertEquals(listOf(name), names(web.run("manga-categories", mangaId(4)), "categories"), "and shows the work in it")
    }

    // =========================================================================== merged sources

    @Test
    fun `a merged source added on the app is shown in the WebUI, and made a reserve there it is a reserve on the app`() {
        val app = device("phone", 5)
        runBlocking {
            app.addMerged(5, 7901, "/extra-a")
            app.addMerged(5, 7902, "/extra-b")
        }
        app.sync()
        assertEquals(listOf("7901", "7902"), names(web.run("merged", mangaId(5)), "sources"), "the WebUI lists them in order")

        web.run("merged-reserve", mangaId(5), "0")
        app.sync()
        assertEquals(listOf(false, true), runBlocking { app.merged(5) }.map { it.updatesEnabled }, "the reserve made in the WebUI reached the app")
    }

    // =========================================================================== deleting

    @Test
    fun `deleting a downloaded chapter in the WebUI with the Karasu box deletes it on the app, without it does not`() {
        val app = device("phone", 6)
        app.download(6, 1)
        app.download(6, 2)
        app.sync() // learns where the deletion log ends

        web.run("delete-chapter", mangaId(6), "Chapter 2", "false")
        app.sync()
        assertTrue(app.isDownloaded(6, 2), "without the box the app keeps its copy")

        web.run("delete-chapter", mangaId(6), "Chapter 1", "true")
        val summary = app.sync()
        assertFalse(app.isDownloaded(6, 1), "with the box the app deletes its copy")
        assertEquals(1, summary.deletionsApplied)
    }

    // =========================================================================== backups and policy

    @Test
    fun `a backup sent by the app is listed in the WebUI, and deleted there it is gone for the app`() {
        val app = Live.api()
        val sent = runBlocking { app.uploadBackup("fullstack.tachibk", "Phone", 4) { byteArrayOf(1, 2, 3, 4).inputStream() } }
        try {
            val shown = names(web.run("backups"), "names")
            assertTrue("fullstack.tachibk" in shown, "the WebUI lists the backup: $shown")

            web.run("delete-backup", "fullstack.tachibk")
            assertTrue(runBlocking { app.listBackups() }.none { it.id == sent.id }, "deleted in the WebUI, gone for the app")
        } finally {
            runCatching { runBlocking { app.deleteBackup(sent.id) } }
        }
    }

    @Test
    fun `the download policy chosen in the WebUI reaches the app`() {
        val app = device("phone", 7)
        val original = runBlocking { api.downloadPolicy() }
        try {
            web.run("policy-set", "SERVER_AND_CLIENTS")
            app.sync()
            assertEquals("SERVER_AND_CLIENTS", app.preferences.serverDownloadPolicy().get())

            web.run("policy-set", "SERVER_ONLY")
            app.sync()
            assertEquals("SERVER_ONLY", app.preferences.serverDownloadPolicy().get())
        } finally {
            runBlocking { api.setDownloadPolicy(original) }
        }
    }

    // =========================================================================== who did it last

    @Test
    fun `a change clicked in the WebUI later than the app's wins, and the app's wins when it was later`() {
        val app = device("phone", 8)
        app.read(8, 1, page = 2)
        app.sync()

        // the app marks it unread a while ago; the WebUI marks it unread-then-read-again afterwards
        app.at(-600_000) { app.read(8, 1, page = 0, read = false) }
        web.run("mark", mangaId(8), "Chapter 1", "unread")
        web.run("mark", mangaId(8), "Chapter 1", "read")
        app.sync()
        assertTrue(app.chapter(8, 1).read, "the later change (the WebUI's reading) is the real one")
        assertEquals(true, chapterOnServer(8, 1).isRead)

        // now the app is the later one
        web.run("mark", mangaId(8), "Chapter 1", "unread")
        app.at(+600_000) { app.read(8, 1, page = 2, read = true) }
        app.sync()
        assertTrue(app.chapter(8, 1).read, "the later change (the app's reading) is the real one")
        assertEquals(true, chapterOnServer(8, 1).isRead, "and the server follows it")
        assertTrue(webSeesRead(8, 1), "and so does the WebUI")
    }

    @Test
    fun `after changes on every side the app, the server and the WebUI agree`() {
        val app = device("phone", 9)
        val tablet = device("tablet", 9)
        val cat = unique("agree")

        app.createCategory(cat)
        app.setCategories(9, cat)
        app.read(9, 1, page = 2)
        runBlocking { app.addMerged(9, 7911, "/x") }
        app.sync()
        web.run("mark", mangaId(9), "Chapter 2", "read")
        tablet.sync()
        tablet.read(9, 2, page = 0, read = false)
        tablet.sync()
        app.sync()

        // the same picture from the app, the server and the WebUI
        assertFalse(app.chapter(9, 2).read, "the tablet's unread reached the app")
        assertFalse(chapterOnServer(9, 2).isRead)
        assertFalse(webSeesRead(9, 2))
        assertTrue(webSeesRead(9, 1))
        assertEquals(listOf(cat), names(web.run("manga-categories", mangaId(9)), "categories"))
        assertEquals(setOf(cat), tablet.categoriesOf(9))
        assertEquals(listOf("7911"), names(web.run("merged", mangaId(9)), "sources"))
        assertTrue(app.sync().isIdle && tablet.sync().isIdle, "and then there is nothing left to say")
        assertEquals(DownloadPolicy.parse("SERVER_ONLY"), DownloadPolicy.parse(app.preferences.serverDownloadPolicy().get().ifEmpty { "SERVER_ONLY" }))
    }
}
