package karasu.domain.suwayomi.interactor

import karasu.data.suwayomi.ServerApiException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The Yatagarasu shelf on the Suwayomi server, from both ends: the app (the real [karasu.data.suwayomi.ServerSyncApi]
 * shelf calls) puts a chapter there and reads back whether it was finished; the "plugin" (the KOReader side, played
 * here with plain HTTP calls as the plugin makes them) downloads the file and reports it read. Mock server by default,
 * a real one with `KARASU_LIVE_URL`.
 *
 * Chapter ids are unique per test (the server's shelf is shared and permanent) and removed when the test ends.
 */
class SyncShelfLiveTest {

    private val app = Live.api(deviceId = "shelf-device-${System.nanoTime()}")
    private val http = OkHttpClient()
    private val used = mutableListOf<Long>()
    private var counter = 0

    private fun newId(): Long = (900_000_000L + (System.nanoTime() % 90_000_000L) * 10 + counter++).also { used += it }

    private fun cbz(label: String, size: Int = 3): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            repeat(size) { page ->
                zip.putNextEntry(ZipEntry("%03d.png".format(page)))
                zip.write("$label-page-$page".toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun upload(id: Long, bytes: ByteArray, url: String = "/live/shelf/$id") = runBlocking {
        val meta = """{"chapterId":$id,"chapterUrl":"$url","mangaTitle":"Shelf test","chapterName":"Chapter 1","chapterNumber":1,"sourceId":0}"""
        app.shelfUpload(meta, "$id.cbz", bytes.size.toLong()) { bytes.inputStream() }
    }

    private fun entry(id: Long) = runBlocking { app.shelf() }.firstOrNull { it.chapterId == id }

    // What the KOReader plugin does, with the same login the app uses.
    private fun plugin(path: String, method: String = "GET", body: String? = null): okhttp3.Response {
        val builder = Request.Builder().url("${Live.url}/api/v1/karasu/$path")
            .header("Authorization", okhttp3.Credentials.basic(Live.user, Live.pass))
        when (method) {
            "PUT" -> builder.put(body!!.toRequestBody("application/json".toMediaType()))
            "HEAD" -> builder.head()
            else -> builder.get()
        }
        return http.newCall(builder.build()).execute()
    }

    @AfterEach
    fun cleanUp() {
        used.forEach { runCatching { runBlocking { app.shelfDelete(it) } } }
    }

    @Test
    fun `a chapter the app puts on the shelf is listed unread and the plugin downloads the same file`() {
        val id = newId()
        val bytes = cbz("download")

        upload(id, bytes)

        val listed = entry(id)
        assertEquals(false, listed?.read, "listed, not read")
        assertEquals("/live/shelf/$id", listed?.chapterUrl)
        plugin("shelf/$id/file").use {
            assertEquals(200, it.code)
            assertArrayEquals(bytes, it.body!!.bytes(), "byte for byte")
        }
    }

    @Test
    fun `finishing the chapter on the plugin shows up on the app as read`() {
        val id = newId()
        upload(id, cbz("finish"))

        plugin("shelf/$id/read", "PUT", """{"read":true}""").use { assertEquals(204, it.code) }

        assertEquals(true, entry(id)?.read)
        plugin("shelf/$id/read", "PUT", """{"read":false}""").use { assertEquals(204, it.code) }
        assertEquals(false, entry(id)?.read, "and back")
    }

    @Test
    fun `sending the same chapter again replaces the file and clears read`() {
        val id = newId()
        upload(id, cbz("first"))
        plugin("shelf/$id/read", "PUT", """{"read":true}""").close()
        val second = cbz("second", size = 5)

        upload(id, second)

        assertEquals(false, entry(id)?.read, "read is zeroed")
        plugin("shelf/$id/file").use { assertArrayEquals(second, it.body!!.bytes()) }
        assertEquals(1, runBlocking { app.shelf() }.count { it.chapterId == id }, "still one entry")
    }

    @Test
    fun `removing a chapter from the shelf removes it and removing again is harmless`() {
        val id = newId()
        upload(id, cbz("remove"))

        runBlocking { app.shelfDelete(id) }
        runBlocking { app.shelfDelete(id) }

        assertEquals(null, entry(id))
        plugin("shelf/$id/file").use { assertEquals(404, it.code) }
    }

    @Test
    fun `a chapter that is not on the shelf cannot be marked read`() {
        val id = newId()

        plugin("shelf/$id/read", "PUT", """{"read":true}""").use { assertEquals(404, it.code) }
        assertFalse(runBlocking { app.shelf() }.any { it.chapterId == id })
    }

    @Test
    fun `a bad request is refused and leaves the shelf alone`() {
        val id = newId()
        upload(id, cbz("keep"))

        plugin("shelf/$id/read", "PUT", """{"read":"true"}""").use { assertEquals(400, it.code, "text is not a boolean") }
        assertEquals(false, entry(id)?.read)

        val failure = assertThrows(ServerApiException::class.java) {
            runBlocking { app.shelfUpload("""{"chapterUrl":"/no-id"}""", "x.cbz", 3) { "abc".byteInputStream() } }
        }
        assertEquals(400, failure.code, "no chapterId")
        assertTrue(entry(id) != null)
    }

    @Test
    fun `putting a chapter on the shelf changes nothing in the library`() {
        val id = newId()
        val before = Live.api(deviceId = "shelf-watch").let { api ->
            var count = 0
            runBlocking { api.forEachLibraryPage { page -> count += page.sumOf { m -> m.chapters.count { c -> c.isRead } } } }
            count
        }

        upload(id, cbz("library"))
        plugin("shelf/$id/read", "PUT", """{"read":true}""").close()

        val after = Live.api(deviceId = "shelf-watch").let { api ->
            var count = 0
            runBlocking { api.forEachLibraryPage { page -> count += page.sumOf { m -> m.chapters.count { c -> c.isRead } } } }
            count
        }
        assertEquals(before, after, "the server does not mark anything read on its own: the app does, through the progress sync")
    }
}
