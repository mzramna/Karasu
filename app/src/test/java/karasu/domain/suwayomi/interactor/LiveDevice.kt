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
import karasu.domain.category.CategoryRepository
import karasu.domain.category.interactor.DeleteCategories
import karasu.domain.category.interactor.GetCategories
import karasu.domain.category.interactor.InsertCategories
import karasu.domain.category.interactor.SetMangaCategories
import karasu.domain.category.interactor.UpdateCategories
import karasu.domain.category.models.CategoryUpdate
import karasu.domain.chapter.ChapterRepository
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.chapter.interactor.UpdateChapter
import karasu.domain.chapter.models.ChapterUpdate
import karasu.domain.extension.repo.ExtensionRepoRepository
import karasu.domain.extension.repo.interactor.CreateExtensionRepo
import karasu.domain.extension.repo.interactor.DeleteExtensionRepo
import karasu.domain.extension.repo.interactor.GetExtensionRepo
import karasu.domain.extension.repo.model.ExtensionRepo
import karasu.domain.history.HistoryRepository
import karasu.domain.history.interactor.GetHistory
import karasu.domain.history.interactor.UpsertHistory
import karasu.domain.manga.MangaRepository
import karasu.domain.manga.interactor.GetManga
import karasu.domain.manga.merged.MergedMangaRepository
import karasu.domain.manga.merged.interactor.MergedSources
import karasu.domain.manga.models.MergedMangaSource
import karasu.domain.operations.OpAction
import karasu.domain.operations.OpEntity
import karasu.domain.operations.OperationLog
import karasu.domain.suwayomi.ServerPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/**
 * Where the server is. By default a mock server inside the test JVM ([MockSuwayomiServer]) so the scenarios
 * run in seconds on every build; with `KARASU_LIVE_URL` they run against a real Suwayomi-Server instead
 * (see `scripts/live-sync-test.sh`). The same scenarios on both is what keeps the mock honest.
 */
object Live {
    private val realUrl: String = System.getenv("KARASU_LIVE_URL").orEmpty()
    val user: String = if (realUrl.isNotEmpty()) System.getenv("KARASU_LIVE_USER").orEmpty() else MockSuwayomiServer.USER
    val pass: String = if (realUrl.isNotEmpty()) System.getenv("KARASU_LIVE_PASS").orEmpty() else MockSuwayomiServer.PASS

    val isReal: Boolean get() = realUrl.isNotEmpty()

    /** The mock, started once for the whole test run and reset between tests. */
    val mock: MockSuwayomiServer by lazy { MockSuwayomiServer().also { it.start() } }

    val url: String get() = if (isReal) realUrl else mock.url

    /** Always true now: there is always something to talk to. Kept so the tests read the same as before. */
    val isConfigured: Boolean get() = true

    /** Where the repositories of the scenarios live (see [RepoHost]); the server fetches them from here too. */
    val repos: RepoHost by lazy { RepoHost() }

    /** The server's own source: the seeded works are all on it. */
    const val SOURCE_ID = 0L

    fun mangaUrl(n: Int) = "/live/m%02d".format(n)

    fun chapterUrl(n: Int, chapter: Int) = "${mangaUrl(n)}/c$chapter"

    /** Chapters 1 and 2 are downloaded on the server (3 pages each); 3 and 4 are not. */
    const val CHAPTERS = 4

    fun api(primary: String = url, secondary: String = "", deviceId: String = ""): ServerSyncApi {
        val reachability = ServerReachability()
        return ServerSyncApi(
            transport = ServerTransport(
                { ServerConfig(primary, secondary, user, pass, deviceId) },
                OkHttpClient(),
                reachability,
            ),
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

/** The extension repositories table of a device, in memory. */
class InMemoryExtensionRepoRepository : ExtensionRepoRepository {
    private val rows = MutableStateFlow<List<ExtensionRepo>>(emptyList())

    override fun subscribeAll(): Flow<List<ExtensionRepo>> = rows
    override suspend fun getAll(): List<ExtensionRepo> = rows.value
    override suspend fun getRepository(baseUrl: String): ExtensionRepo? = rows.value.firstOrNull { it.baseUrl == baseUrl }
    override suspend fun getRepositoryBySigningKeyFingerprint(fingerprint: String): ExtensionRepo? =
        rows.value.firstOrNull { it.signingKeyFingerprint == fingerprint }

    override fun getCount(): Flow<Int> = rows.map { it.size }

    override suspend fun insertRepository(baseUrl: String, name: String, shortName: String?, website: String, signingKeyFingerprint: String) {
        rows.value = rows.value + ExtensionRepo(baseUrl, name, shortName, website, signingKeyFingerprint)
    }

    override suspend fun upsertRepository(baseUrl: String, name: String, shortName: String?, website: String, signingKeyFingerprint: String) {
        rows.value = rows.value.filterNot { it.baseUrl == baseUrl } + ExtensionRepo(baseUrl, name, shortName, website, signingKeyFingerprint)
    }

    override suspend fun replaceRepository(newRepo: ExtensionRepo) = upsertRepository(newRepo)

    override suspend fun deleteRepository(baseUrl: String) {
        rows.value = rows.value.filterNot { it.baseUrl == baseUrl }
    }
}

/**
 * One phone or tablet: the local database (works, chapters, categories, history, merged sources,
 * downloads) held in memory, behind the same interactors the app uses — the writing ones are the real
 * ones, so what they record in the operation log is what the app would record — talking to the server
 * through the real [ServerSyncApi]. [sync] is what the background job does.
 *
 * Each device has its own preferences (so its own cursors and snapshots), its own id, and its own clock,
 * which is what makes two of them behave like two physical devices that share nothing but the server.
 */
class LiveDevice(
    val label: String,
    deviceId: String = "device-$label-${System.nanoTime()}",
    val api: ServerSyncApi = Live.api(deviceId = deviceId),
) {

    val preferences = ServerPreferences(InMemoryPreferenceStore()).apply {
        deviceId().set(deviceId)
        enabled().set(true)
        syncCategories().set(true)
        syncProgress().set(true)
        syncHistory().set(true)
        syncMergedSources().set(true)
        applyRemoteDeletions().set(true)
        pushLocalDeletions().set(true)
    }

    /** How far this device's clock is from the real one; tests move it to say "I did this a while ago". */
    var clockOffsetMs = 0L
    val operations = OperationLog(preferences) { System.currentTimeMillis() + clockOffsetMs }

    // --- the local database
    val mangas = mutableListOf<Manga>()
    private val chapters = mutableMapOf<Long, MutableList<Chapter>>()
    private val categories = mutableListOf<Category>()
    private val links = mutableMapOf<Long, MutableSet<Long>>()
    private val history = mutableMapOf<Long, History>()
    val downloaded = mutableSetOf<Long>()
    val mergedRepository = InMemoryMergedRepository()
    private val repoRepository = InMemoryExtensionRepoRepository()

    private var nextId = 1L

    // --- repositories (state) under the real interactors
    private val chapterRepository = mockk<ChapterRepository>(relaxed = true)
    private val historyRepository = mockk<HistoryRepository>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val mangaRepository = mockk<MangaRepository>(relaxed = true)

    // --- real interactors (the ones that write, so they record)
    private val updateChapter = UpdateChapter(chapterRepository, operations)
    private val upsertHistory = UpsertHistory(historyRepository, operations)
    private val insertCategories = InsertCategories(categoryRepository, operations)
    private val updateCategories = UpdateCategories(categoryRepository, operations)
    private val deleteCategories = DeleteCategories(categoryRepository, operations)
    private val setMangaCategories = SetMangaCategories(mangaRepository, categoryRepository, operations)
    private val mergedSources = MergedSources(mergedRepository, operations)
    private val getExtensionRepo = GetExtensionRepo(repoRepository)
    private val deleteExtensionRepo = DeleteExtensionRepo(repoRepository, operations)

    /**
     * Adding a repository fetches its index over https from the internet; here it is fetched from [Live.repos]
     * instead, and what is written and recorded is what the real interactor writes and records.
     */
    private val createExtensionRepo = mockk<CreateExtensionRepo>()

    // --- readers and the rest, answering from the same state
    private val getManga = mockk<GetManga>(relaxed = true)
    private val getChapter = mockk<GetChapter>(relaxed = true)
    private val getCategories = mockk<GetCategories>(relaxed = true)
    private val getHistory = mockk<GetHistory>(relaxed = true)
    private val downloadManager = mockk<DownloadManager>(relaxed = true)
    private val sourceManager = mockk<SourceManager>(relaxed = true)

    init {
        // repositories: the writes the real interactors perform
        coEvery { chapterRepository.update(any()) } answers {
            apply(listOf(firstArg<ChapterUpdate>()))
            true
        }
        coEvery { chapterRepository.updateAll(any()) } answers {
            apply(firstArg<List<ChapterUpdate>>())
            true
        }
        coEvery { historyRepository.upsert(any(), any(), any()) } answers {
            val chapterId = firstArg<Long>()
            val entry = history.getOrPut(chapterId) { History.create().apply { chapter_id = chapterId } }
            entry.last_read = secondArg<Long>()
            entry.time_read += thirdArg<Long>()
            chapterId
        }
        coEvery { categoryRepository.getAll() } answers { categories.toList() }
        coEvery { categoryRepository.getAllByMangaId(any()) } answers {
            val ids = links[firstArg<Long>()].orEmpty()
            categories.filter { it.id?.toLong() in ids }
        }
        coEvery { categoryRepository.insert(any()) } answers {
            val category = firstArg<Category>()
            val id = nextId++
            category.id = id.toInt()
            categories += category
            id
        }
        coEvery { categoryRepository.update(any()) } answers {
            val update = firstArg<CategoryUpdate>()
            categories.firstOrNull { it.id?.toLong() == update.id }?.let { c -> update.name?.let { c.name = it } }
            true
        }
        coEvery { categoryRepository.delete(any()) } answers {
            val id = firstArg<Long>()
            categories.removeAll { it.id?.toLong() == id }
            links.values.forEach { it.remove(id) }
        }
        coEvery { mangaRepository.setCategories(any(), any()) } answers {
            links[firstArg<Long>()] = secondArg<List<Long>>().toMutableSet()
        }

        coEvery { createExtensionRepo.await(any()) } coAnswers {
            val base = firstArg<String>().trim().trimEnd('/').removeSuffix("/repo.json").trimEnd('/')
            when {
                repoRepository.getRepository(base) != null -> CreateExtensionRepo.Result.RepoAlreadyExists
                !RepoHost.answers("$base/repo.json") -> CreateExtensionRepo.Result.InvalidUrl
                else -> {
                    repoRepository.insertRepository(base, "Repository", null, "https://example.test", "key-$base")
                    operations.record(OpEntity.EXTENSION_REPO, OpAction.SET, name = base)
                    CreateExtensionRepo.Result.Success
                }
            }
        }

        // readers
        coEvery { getManga.awaitFavorites() } answers { mangas.toList() }
        coEvery { getManga.awaitById(any()) } answers { mangas.firstOrNull { it.id == firstArg<Long>() } }
        coEvery { getManga.awaitByUrlAndSource(any(), any()) } answers {
            mangas.firstOrNull { it.url == firstArg<String>() && it.source == secondArg<Long>() }
        }
        coEvery { getChapter.awaitAllRaw(any(), any()) } answers { chapters[firstArg<Long>()].orEmpty().toList() }
        coEvery { getChapter.awaitById(any()) } answers { chapters.values.flatten().firstOrNull { it.id == firstArg<Long>() } }
        coEvery { getCategories.await() } answers { categories.toList() }
        coEvery { getCategories.awaitByMangaId(any()) } answers {
            val ids = links[firstArg<Long?>()].orEmpty()
            categories.filter { it.id?.toLong() in ids }
        }
        coEvery { getHistory.awaitAllByMangaId(any()) } answers {
            chapters[firstArg<Long>()].orEmpty().mapNotNull { history[it.id] }
        }
        every { downloadManager.isChapterDownloaded(any(), any(), any()) } answers { firstArg<Chapter>().id in downloaded }
        every { downloadManager.deleteChapters(any(), any(), any(), any()) } answers {
            firstArg<List<Chapter>>().forEach { downloaded -= it.id!! }
        }
    }

    private fun apply(updates: List<ChapterUpdate>) {
        updates.forEach { update ->
            val chapter = chapters.values.flatten().first { it.id == update.id }
            update.read?.let { chapter.read = it }
            update.lastPageRead?.let { chapter.last_page_read = it.toInt() }
        }
    }

    /** Runs what the user does at another moment of this device's clock, then puts the clock back. */
    fun <T> at(offsetMs: Long, action: () -> T): T {
        val before = clockOffsetMs
        clockOffsetMs = offsetMs
        return try {
            action()
        } finally {
            clockOffsetMs = before
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

    // --- categories (through the interactors the screens use)
    fun createCategory(name: String): Category =
        Category.create(name).also { category -> runBlocking { insertCategories.awaitOne(category) } }

    fun deleteCategory(name: String) = runBlocking {
        categories.filter { it.name == name }.forEach { deleteCategories.awaitOne(it.id!!.toLong()) }
    }

    fun renameCategory(old: String, new: String) = runBlocking {
        val id = categories.first { it.name == old }.id!!.toLong()
        updateCategories.awaitOne(CategoryUpdate(id = id, name = new))
    }

    fun categoryNames(): Set<String> = categories.mapTo(mutableSetOf()) { it.name }

    fun setCategories(n: Int, vararg names: String) = runBlocking {
        setMangaCategories.await(mangaOf(n).id!!, names.map { name -> categories.first { it.name == name }.id!!.toLong() })
    }

    fun categoriesOf(n: Int): Set<String> {
        val ids = links[mangaOf(n).id!!].orEmpty()
        return categories.filter { it.id?.toLong() in ids }.mapTo(mutableSetOf()) { it.name }
    }

    // --- reading
    fun read(n: Int, k: Int, page: Int, read: Boolean = true) = runBlocking {
        updateChapter.awaitAll(listOf(ChapterUpdate(id = chapter(n, k).id!!, read = read, lastPageRead = page.toLong())))
    }

    // --- history (milliseconds, as in the app)
    fun readAt(n: Int, k: Int, millis: Long) = runBlocking {
        upsertHistory.await(chapter(n, k).id!!, millis, 0L)
    }

    /** What "remove from history" does in the app's recents screen. */
    fun removeFromHistory(n: Int, k: Int) = runBlocking {
        val entry = history[chapter(n, k).id] ?: return@runBlocking
        entry.last_read = 0L
        entry.time_read = 0L
        upsertHistory.await(entry)
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

    // --- extension repositories (the base url is the index url without "/repo.json")

    /** What the user does in the repositories screen: types the address and confirms. */
    fun addRepo(base: String): CreateExtensionRepo.Result = runBlocking { createExtensionRepo.await("$base/repo.json") }

    fun removeRepo(base: String) = runBlocking { deleteExtensionRepo.await(base) }

    fun repoBases(): Set<String> = runBlocking { getExtensionRepo.getAll().mapTo(mutableSetOf()) { it.baseUrl } }

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
            updateCategories, operations, getExtensionRepo, createExtensionRepo, deleteExtensionRepo,
        ).await()
    }
}
