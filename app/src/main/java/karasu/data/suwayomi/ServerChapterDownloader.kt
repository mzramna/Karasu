package karasu.data.suwayomi

import co.touchlab.kermit.Logger
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.domain.manga.models.Manga
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import karasu.domain.suwayomi.ServerPreferences
import karasu.domain.suwayomi.models.DownloadPolicy
import kotlinx.coroutines.CancellationException

/**
 * Copies a chapter the server has already downloaded instead of fetching it from the source.
 *
 * This is an optimisation that must never be a failure mode: every problem — server off, address
 * wrong, chapter not there, a transfer cut short — comes back as `false` and the caller downloads
 * from the source exactly as it would have without this. Only cancellation is rethrown.
 */
class ServerChapterDownloader(
    private val api: ServerSyncApi,
    private val preferences: ServerPreferences,
) {

    /**
     * Whether a download should even ask the server.
     *
     * Either the user asked for it, or the server says it is the only one supposed to download
     * (`SERVER_ONLY`) — in which case going to it first is simply the polite thing to do. A server
     * that was unreachable moments ago is skipped, so a chapter queue does not pay the connect
     * timeout once per chapter.
     */
    fun isActive(): Boolean {
        if (!api.isEnabled) return false
        val wanted = preferences.preferServerDownload().get() ||
            DownloadPolicy.parse(preferences.serverDownloadPolicy().get()) == DownloadPolicy.SERVER_ONLY
        return wanted && !api.reachability.shouldSkipOptional()
    }

    /**
     * Tries to put the chapter into [mangaDir] under [dirName], in the layout the user chose:
     * a `.cbz` file, or a folder of page images.
     *
     * @return true when the chapter is on disk and complete, false when the caller must download it.
     */
    suspend fun tryFetch(manga: Manga, chapter: Chapter, mangaDir: UniFile, dirName: String, asCbz: Boolean): Boolean {
        if (!isActive()) return false
        return try {
            val lookup = api.lookupChapter(manga.source, manga.url, chapter.url)
            if (lookup == null || !lookup.downloaded) return false
            val done = api.openChapterArchive(lookup.chapterId) { stream, length ->
                if (asCbz) saveAsCbz(stream, length, mangaDir, dirName) else saveAsFolder(stream, mangaDir, dirName)
            }
            done == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.i(e) { "Server download of ${chapter.url} failed, using the source" }
            false
        }
    }

    private fun saveAsCbz(stream: InputStream, length: Long, mangaDir: UniFile, dirName: String): Boolean {
        val tmp = mangaDir.createFile("$dirName.cbz$TMP_SUFFIX")
        if (tmp?.isFile != true) throw IOException("Could not create $dirName.cbz")
        var complete = false
        try {
            val written = tmp.openOutputStream().use { out -> stream.copyTo(out) }
            // A server that announced a length and delivered less was cut off; a CBZ missing its
            // central directory would be unreadable and still look like a finished download.
            if (written <= 0 || (length >= 0 && written != length)) {
                throw IOException("Incomplete archive: $written of $length bytes")
            }
            complete = tmp.renameTo("$dirName.cbz")
            return complete
        } finally {
            if (!complete) tmp.delete()
        }
    }

    /** "Save as CBZ" off: unpack the archive into a page folder, the layout the downloader itself makes. */
    private fun saveAsFolder(stream: InputStream, mangaDir: UniFile, dirName: String): Boolean {
        val tmp = mangaDir.createDirectory("$dirName$TMP_SUFFIX") ?: throw IOException("Could not create $dirName")
        var complete = false
        try {
            var pages = 0
            ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    // Flattened: the downloader's own folders have no subdirectories, and an entry
                    // name is untrusted input that must not be able to climb out of the folder.
                    val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    if (name.isBlank() || name == "." || name == "..") continue
                    val file = tmp.createFile(name) ?: throw IOException("Could not create $name")
                    file.openOutputStream().use { out -> zip.copyTo(out) }
                    pages++
                }
            }
            if (pages == 0) throw IOException("Empty archive")
            complete = tmp.renameTo(dirName)
            return complete
        } finally {
            if (!complete) tmp.delete()
        }
    }

    private companion object {
        /** Same suffix the downloader uses for unfinished work, so a crash leaves what it already cleans up. */
        const val TMP_SUFFIX = "_tmp"
    }
}
