package karasu.domain.suwayomi.interactor

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import karasu.data.suwayomi.ServerConfig
import karasu.data.suwayomi.ServerReachability
import karasu.data.suwayomi.ServerSyncApi
import karasu.data.suwayomi.ServerTransport
import karasu.domain.category.interactor.GetCategories
import karasu.domain.category.interactor.InsertCategories
import karasu.domain.category.interactor.SetMangaCategories
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.chapter.interactor.UpdateChapter
import karasu.domain.history.interactor.GetHistory
import karasu.domain.history.interactor.UpsertHistory
import karasu.domain.manga.interactor.GetManga
import karasu.domain.manga.merged.MergedMangaRepository
import karasu.domain.manga.merged.interactor.MergedSources
import karasu.domain.manga.models.MergedMangaSource
import karasu.domain.suwayomi.ServerPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/** Where the real server is, read from the environment. See [SyncLiveBidirectionalTest]. */
object Live {
    val url: String = System.getenv("KARASU_LIVE_URL").orEmpty()
    val user: String = System.getenv("KARASU_LIVE_USER").orEmpty()
    val pass: String = System.getenv("KARASU_LIVE_PASS").orEmpty()
    val isConfigured: Boolean get() = url.isNotEmpty()

    /** The server's own source: the seeded works are all on it. */
    const val SOURCE_ID = 0L

    fun mangaUrl(n: Int) = "/live/m%02d".format(n)

    fun chapterUrl(n: Int, chapter: Int) = "${mangaUrl(n)}/c$chapter"

    /** Chapters 1 and 2 are downloaded on the server (3 pages each); 3 and 4 are not. */
    const val CHAPTERS = 4

    fun api(primary: String = url, secondary: String = ""): ServerSyncApi {
        val reachability = ServerReachability()
        return ServerSyncApi(
            transport = ServerTransport({ ServerConfig(primary, secondary, user, pass) }, OkHttpClient(), reachability),
            reachability = reachability,
        )
    }
}

/** The merged sources table of a device, in memory. */
class InMemoryMergedRepository : MergedMangaRepository {
    private val rows = mutableListOf<MergedMangaSource>()
    private var nextId = 1L

    override suspend fun getByMangaId(mangaId: Long): List<MergedMangaSource> =
        rows.filter { it.mangaId == mangaId }.sortedBy { it.priority }

    override suspend fun getMangaIdsWithMerges(): Set<Long> = rows.mapTo(mutableSetOf()) { it.mangaId }

    override suspend fun insert(mangaId: Long, source: Long, url: String, priority: Int) {
        rows += MergedMangaSource(nextId++, mangaId, source, url, priority, true)
    }

    override suspend fun updatePriority(mangaId: Long, source: Long, priority: Int) = replace(mangaId, source) {
        it.copy(priority = priority)
    }

    override suspend fun updateUpdatesEnabled(mangaId: Long, source: Long, enabled: Boolean) = replace(mangaId, source) {
        it.copy(updatesEnabled = enabled)
    }

    override suspend fun delete(mangaId: Long, source: Long) {
        rows.removeAll { it.mangaId == mangaId && it.source == source }
    }

    override suspend fun deleteByMangaId(mangaId: Long) {
        rows.removeAll { it.mangaId == mangaId }
    }

    private fun replace(mangaId: Long, source: Long, change: (MergedMangaSource) -> MergedMangaSource) {
        val index = rows.indexOfFirst { it.mangaId == mangaId && it.source == source }
        if (index >= 0) rows[index] = change(rows[index])
    }
}

/**
 * One phone or tablet: the local database (works, chapters, categories, history, merged sources,
 * downloads) held in memory, behind the same interactors the app uses, talking to the real server
 * through the real [ServerSyncApi]. [sync] is what the background job does.
 *
 * Each device has its own preferences (so its own cursors and snapshots), which is what makes two
 * of them behave like two physical devices that share nothing but the server.
 */
class LiveDevice(val label: String, val api: ServerSyncApi = Live.api()) {

    val preferences = ServerPreferences(InMemoryPreferenceStore()).apply {
        enabled().set(true)
        syncCategories().set(true)
        syncProgress().set(true)
        syncHistory().set(true)
        syncMergedSources().set(true)
        applyRemoteDeletions().set(true)
        pushLocalDeletions().set(true)
    }

    // --- the local database
    val mangas = mutableListOf<Manga>()
    private val chapters = mutableMapOf<Long, MutableList<Chapter>>()
    private val categories = mutableListOf<Category>()
    private val links = mutableMapOf<Long, MutableSet<Long>>()
    private val history = mutableMapOf<Long, History>()
    val downloaded = mutableSetOf<Long>()
    val mergedRepository = InMemoryMergedRepository()
    private val mergedSources = MergedSources(mergedRepository)

    private var nextId = 1L

    private val getManga = mockk<GetManga>(relaxed = true)
    private val getChapter = mockk<GetChapter>(relaxed = true)
    private val updateChapter = mockk<UpdateChapter>(relaxed = true)
    private val getCategories = mockk<GetCategories>(relaxed = true)
    private val insertCategories = mockk<InsertCategories>(relaxed = true)
    private val setMangaCategories = mockk<SetMangaCategories>(relaxed = true)
    private val downloadManager = mockk<DownloadManager>(relaxed = true)
    private val sourceManager = mockk<SourceManager>(relaxed = true)
    private val getHistory = mockk<GetHistory>(relaxed = true)
    private val upsertHistory = mockk<UpsertHistory>(relaxed = true)

    init {
        coEvery { getManga.awaitFavorites() } answers { mangas.toList() }
        coEvery { getManga.awaitByUrlAndSource(any(), any()) } answers {
            mangas.firstOrNull { it.url == firstArg<String>() && it.source == secondArg<Long>() }
        }
        coEvery { getChapter.awaitAllRaw(any(), any()) } answers { chapters[firstArg<Long>()].orEmpty().toList() }
        coEvery { updateChapter.awaitAll(any()) } answers {
            firstArg<List<karasu.domain.chapter.models.ChapterUpdate>>().forEach { update ->
                val chapter = chapters.values.flatten().first { it.id == update.id }
                update.read?.let { chapter.read = it }
                update.lastPageRead?.let { chapter.last_page_read = it.toInt() }
            }
            true
        }
        coEvery { getCategories.await() } answers { categories.toList() }
        coEvery { getCategories.awaitByMangaId(any()) } answers {
            val ids = links[firstArg<Long?>()].orEmpty()
            categories.filter { it.id?.toLong() in ids }
        }
        coEvery { insertCategories.awaitOne(any()) } answers {
            val category = firstArg<Category>()
            val id = nextId++
            category.id = id.toInt()
            categories += category
            id
        }
        coEvery { setMangaCategories.await(any(), any()) } answers {
            links[firstArg<Long?>()!!] = secondArg<List<Long>>().toMutableSet()
        }
        every { downloadManager.isChapterDownloaded(any(), any(), any()) } answers { firstArg<Chapter>().id in downloaded }
        every { downloadManager.deleteChapters(any(), any(), any(), any()) } answers {
            firstArg<List<Chapter>>().forEach { downloaded -= it.id!! }
        }
        coEvery { getHistory.awaitAllByMangaId(any()) } answers {
            chapters[firstArg<Long>()].orEmpty().mapNotNull { history[it.id] }
        }
        coEvery { upsertHistory.await(any<Long>(), any(), any()) } answers {
            val chapterId = firstArg<Long>()
            val entry = history.getOrPut(chapterId) { History.create().apply { chapter_id = chapterId } }
            entry.last_read = secondArg<Long>()
            entry.time_read += thirdArg<Long>()
            chapterId
        }
    }

    // --- building the local state

    /** A work of the library, with the chapters the seeded server work has (c1..c4). */
    fun addManga(n: Int): Manga {
        val manga = Manga.create(Live.mangaUrl(n), "Live M%02d".format(n), Live.SOURCE_ID).apply { id = nextId++ }
        mangas += manga
        chapters[manga.id!!] = (1..Live.CHAPTERS).mapTo(mutableListOf()) { k ->
            Chapter.create().apply {
                id = nextId++
                manga_id = manga.id
                url = Live.chapterUrl(n, k)
                name = "Chapter $k"
                chapter_number = k.toFloat()
            }
        }
        return manga
    }

    fun mangaOf(n: Int): Manga = mangas.first { it.url == Live.mangaUrl(n) }

    fun chapter(n: Int, k: Int): Chapter = chapters.getValue(mangaOf(n).id!!).first { it.url == Live.chapterUrl(n, k) }

    // --- categories
    fun createCategory(name: String): Category {
        val category = Category.create(name).apply { id = nextId++.toInt() }
        categories += category
        return category
    }

    fun deleteCategory(name: String) {
        val gone = categories.filter { it.name == name }
        categories.removeAll(gone)
        links.values.forEach { set -> set.removeAll(gone.mapNotNull { it.id?.toLong() }.toSet()) }
    }

    fun categoryNames(): Set<String> = categories.mapTo(mutableSetOf()) { it.name }

    fun setCategories(n: Int, vararg names: String) {
        links[mangaOf(n).id!!] = names.mapTo(mutableSetOf()) { name -> categories.first { it.name == name }.id!!.toLong() }
    }

    fun categoriesOf(n: Int): Set<String> {
        val ids = links[mangaOf(n).id!!].orEmpty()
        return categories.filter { it.id?.toLong() in ids }.mapTo(mutableSetOf()) { it.name }
    }

    // --- reading
    fun read(n: Int, k: Int, page: Int, read: Boolean = true) {
        chapter(n, k).apply {
            this.read = read
            last_page_read = page
        }
    }

    // --- history (milliseconds, as in the app)
    fun readAt(n: Int, k: Int, millis: Long) {
        val id = chapter(n, k).id!!
        history.getOrPut(id) { History.create().apply { chapter_id = id } }.last_read = millis
    }

    /** What "remove from history" does in the app's recents screen. */
    fun removeFromHistory(n: Int, k: Int) {
        history[chapter(n, k).id]?.apply {
            last_read = 0L
            time_read = 0L
        }
    }

    fun lastRead(n: Int, k: Int): Long = history[chapter(n, k).id]?.last_read ?: 0L

    // --- merged sources
    suspend fun merged(n: Int) = mergedSources.await(mangaOf(n).id!!)

    suspend fun addMerged(n: Int, source: Long, url: String) {
        val id = mangaOf(n).id!!
        mergedSources.add(id, source, url, mergedSources.await(id).size + 1)
    }

    suspend fun removeMerged(n: Int, source: Long) = mergedSources.remove(mangaOf(n).id!!, source)

    suspend fun reserve(n: Int, source: Long, reserve: Boolean) = mergedSources.setReserve(mangaOf(n).id!!, source, reserve)

    /** The order the user dragged them into, applied the way the app's own screen does: 1, 2, 3... */
    suspend fun reorderMerged(n: Int, vararg inOrder: Long) {
        inOrder.forEachIndexed { index, source -> mergedSources.reorder(mangaOf(n).id!!, source, index + 1) }
    }

    // --- downloads
    fun download(n: Int, k: Int) {
        downloaded += chapter(n, k).id!!
    }

    fun deleteDownload(n: Int, k: Int) {
        downloaded -= chapter(n, k).id!!
    }

    fun isDownloaded(n: Int, k: Int) = chapter(n, k).id in downloaded

    // --- the sync itself, as the background job runs it

    fun sync(): SyncSuwayomiServer.Summary = runBlocking {
        SyncSuwayomiServer(
            api, preferences, getManga, getChapter, updateChapter, getCategories, insertCategories,
            setMangaCategories, mergedSources, mergedRepository, downloadManager, sourceManager, getHistory, upsertHistory,
        ).await()
    }
}
