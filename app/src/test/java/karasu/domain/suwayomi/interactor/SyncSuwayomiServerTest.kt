package karasu.domain.suwayomi.interactor

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import karasu.data.suwayomi.RemoteCategory
import karasu.data.suwayomi.RemoteChapter
import karasu.data.suwayomi.RemoteManga
import karasu.data.suwayomi.ServerSyncApi
import karasu.domain.category.interactor.GetCategories
import karasu.domain.category.interactor.InsertCategories
import karasu.domain.category.interactor.SetMangaCategories
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.chapter.interactor.UpdateChapter
import karasu.domain.chapter.models.ChapterUpdate
import karasu.data.suwayomi.ServerApiException
import karasu.domain.extension.repo.interactor.CreateExtensionRepo
import karasu.domain.extension.repo.interactor.DeleteExtensionRepo
import karasu.domain.extension.repo.interactor.GetExtensionRepo
import karasu.domain.extension.repo.model.ExtensionRepo
import karasu.domain.history.interactor.GetHistory
import karasu.domain.history.interactor.UpsertHistory
import karasu.domain.category.interactor.UpdateCategories
import karasu.domain.operations.OperationLog
import karasu.domain.manga.interactor.GetManga
import karasu.domain.manga.interactor.InsertManga
import karasu.domain.manga.interactor.UpdateManga
import karasu.domain.chapter.interactor.InsertChapter
import eu.kanade.tachiyomi.data.cache.CoverCache
import karasu.domain.manga.merged.MergedMangaRepository
import karasu.domain.manga.merged.interactor.MergedSources
import karasu.domain.suwayomi.ServerPreferences
import karasu.domain.suwayomi.models.DeletionEntry
import karasu.domain.suwayomi.models.DownloadPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SyncSuwayomiServerTest {

    private val api = mockk<ServerSyncApi>(relaxed = true)
    private val preferences = ServerPreferences(InMemoryPreferenceStore())
    private val getManga = mockk<GetManga>(relaxed = true)
    private val getChapter = mockk<GetChapter>(relaxed = true)
    private val updateChapter = mockk<UpdateChapter>(relaxed = true)
    private val getCategories = mockk<GetCategories>(relaxed = true)
    private val insertCategories = mockk<InsertCategories>(relaxed = true)
    private val setMangaCategories = mockk<SetMangaCategories>(relaxed = true)
    private val mergedSources = mockk<MergedSources>(relaxed = true)
    private val mergedRepository = mockk<MergedMangaRepository>(relaxed = true)
    private val downloadManager = mockk<DownloadManager>(relaxed = true)
    private val sourceManager = mockk<SourceManager>(relaxed = true)
    private val getHistory = mockk<GetHistory>(relaxed = true)
    private val upsertHistory = mockk<UpsertHistory>(relaxed = true)
    private val updateCategories = mockk<UpdateCategories>(relaxed = true)
    private val operations = OperationLog(preferences)
    private val getExtensionRepo = mockk<GetExtensionRepo>(relaxed = true)
    private val createExtensionRepo = mockk<CreateExtensionRepo>(relaxed = true)
    private val deleteExtensionRepo = mockk<DeleteExtensionRepo>(relaxed = true)
    private val insertManga = mockk<InsertManga>(relaxed = true)
    private val insertChapter = mockk<InsertChapter>(relaxed = true)
    private val updateManga = mockk<UpdateManga>(relaxed = true)
    private val coverCache = mockk<CoverCache>(relaxed = true)

    private val manga = Manga.create("/m", "Title", 3L).apply { id = 1L }

    private fun sync() = SyncSuwayomiServer(
        api, preferences, getManga, getChapter, updateChapter, getCategories, insertCategories,
        setMangaCategories, mergedSources, mergedRepository, downloadManager, sourceManager, getHistory, upsertHistory,
        updateCategories, operations, getExtensionRepo, createExtensionRepo, deleteExtensionRepo,
        insertManga, insertChapter, updateManga, coverCache,
    )

    private fun chapter(id: Long, url: String, read: Boolean = false, page: Int = 0): Chapter =
        Chapter.create().apply {
            this.id = id
            this.url = url
            this.read = read
            this.last_page_read = page
        }

    private fun category(id: Int, name: String): Category = Category.create(name).apply { this.id = id }

    /** Only the parts under test are switched on; everything else is a no-op. */
    @BeforeEach
    fun setUp() {
        every { api.isEnabled } returns true
        coEvery { api.downloadPolicy() } returns DownloadPolicy.SERVER_AND_CLIENTS
        preferences.applyRemoteDeletions().set(false)
        preferences.syncMergedSources().set(false)
        preferences.syncCategories().set(false)
        preferences.syncProgress().set(false)
        preferences.syncHistory().set(false)
        preferences.pushLocalDeletions().set(false)
        coEvery { getManga.awaitFavorites() } returns listOf(manga)
    }

    private fun serverLibrary(vararg remote: RemoteManga) {
        coEvery { api.forEachLibraryPage(any(), any()) } coAnswers {
            secondArg<suspend (List<RemoteManga>) -> Unit>().invoke(remote.toList())
        }
    }

    @Test
    fun `nothing runs while sync is off`() = runBlocking {
        every { api.isEnabled } returns false
        sync().await()
        coVerify(exactly = 0) { api.downloadPolicy() }
    }

    @Test
    fun `the download policy is remembered for the downloader`() = runBlocking {
        coEvery { api.downloadPolicy() } returns DownloadPolicy.SERVER_ONLY
        sync().await()
        assertEquals("SERVER_ONLY", preferences.serverDownloadPolicy().get())
    }

    @Test
    fun `progress only moves forward and each side gets what it lacks`() = runBlocking {
        preferences.syncProgress().set(true)
        coEvery { getChapter.awaitAllRaw(1L, false) } returns listOf(
            chapter(10, "/c1", read = false, page = 0), // read on the server
            chapter(11, "/c2", read = true, page = 5), // unread on the server
            chapter(12, "/c3", read = false, page = 2), // identical
            chapter(13, "/only-here"),
        )
        serverLibrary(
            RemoteManga(
                id = 100, sourceId = "3", url = "/m", categoryIds = emptySet(),
                chapters = listOf(
                    RemoteChapter(1001, "/c1", isRead = true, lastPageRead = 9),
                    RemoteChapter(1002, "/c2", isRead = false, lastPageRead = 1),
                    RemoteChapter(1003, "/c3", isRead = false, lastPageRead = 2),
                    RemoteChapter(1004, "/only-there", isRead = true, lastPageRead = 4),
                ),
            ),
        )

        val summary = sync().await()

        coVerify { updateChapter.awaitAll(listOf(ChapterUpdate(id = 10, read = true, lastPageRead = 9))) }
        coVerify { api.updateChapters(listOf(ServerSyncApi.ChapterPatch(1002, true, 5))) }
        assertEquals(1, summary.chaptersPulled)
        assertEquals(1, summary.chaptersPushed)
    }

    @Test
    fun `a server library manga the device lacks is imported with its chapters`() = runBlocking {
        preferences.syncProgress().set(true)
        coEvery { getManga.awaitByUrlAndSource("/new", 7L) } returns null
        coEvery { insertManga.await(any()) } returns 55L
        coEvery { getChapter.awaitAllRaw(55L, false) } returns emptyList()
        serverLibrary(
            RemoteManga(
                id = 100, sourceId = "7", url = "/new", categoryIds = emptySet(), title = "New",
                chapters = listOf(RemoteChapter(1, "/c1", name = "Ch 1", chapterNumber = 1f)),
            ),
        )

        sync().await()

        coVerify { insertManga.await(withArg { it.url == "/new" && it.favorite }) }
        coVerify { insertChapter.awaitBulk(withArg { it.size == 1 && it[0].url == "/c1" && it[0].manga_id == 55L }) }
    }

    @Test
    fun `the server's cover of an imported manga is kept on the device`() = runBlocking {
        val target = java.io.File.createTempFile("cover", ".jpg").apply { delete() }
        val path = "/api/v1/manga/100/thumbnail"
        preferences.syncProgress().set(true)
        coEvery { getManga.awaitByUrlAndSource("/new", 7L) } returns null
        coEvery { insertManga.await(any()) } returns 55L
        coEvery { getChapter.awaitAllRaw(55L, false) } returns emptyList()
        coEvery { coverCache.getCoverFile(path) } returns target
        serverLibrary(RemoteManga(100, "7", "/new", emptySet(), emptyList(), thumbnailUrl = path))

        sync().await()

        coVerify { api.downloadCover(path, target) }
    }

    @Test
    fun `a local manga the server does not have is left alone`() = runBlocking {
        preferences.syncProgress().set(true)
        serverLibrary(RemoteManga(100, "3", "/other", emptySet(), listOf(RemoteChapter(1, "/c", true, 3))))
        sync().await()
        coVerify(exactly = 0) { getChapter.awaitAllRaw(1L, any()) }
        coVerify(exactly = 0) { api.updateChapters(any()) }
    }

    @Test
    fun `the first run only learns where the deletion log ends`() = runBlocking {
        preferences.applyRemoteDeletions().set(true)
        coEvery { api.deletions(0) } returns listOf(
            DeletionEntry(3, "3", "/m", "/c1"),
            DeletionEntry(7, "3", "/m", "/c2"),
        )

        sync().await()

        assertEquals(7L, preferences.lastDeletionId().get())
        verify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
    }

    @Test
    fun `later runs delete the downloaded chapter and move the cursor`() = runBlocking {
        preferences.applyRemoteDeletions().set(true)
        preferences.lastDeletionId().set(7)
        val downloaded = chapter(10, "/c1")
        coEvery { api.deletions(7) } returns listOf(
            DeletionEntry(9, "3", "/m", "/c1"),
            DeletionEntry(10, "3", "/m", "/gone-locally"),
            DeletionEntry(11, "999", "/unknown", "/c"),
        )
        coEvery { getManga.awaitByUrlAndSource("/m", 3L) } returns manga
        coEvery { getChapter.awaitAllRaw(1L, false) } returns listOf(downloaded)
        every { downloadManager.isChapterDownloaded(downloaded, manga, true) } returns true

        val summary = sync().await()

        val deleted = slot<List<Chapter>>()
        verify(exactly = 1) { downloadManager.deleteChapters(capture(deleted), manga, any(), true) }
        assertEquals(listOf(downloaded), deleted.captured)
        assertEquals(11L, preferences.lastDeletionId().get())
        assertEquals(1, summary.deletionsApplied)
    }

    @Test
    fun `categories missing on either side are created there`() = runBlocking {
        preferences.syncCategories().set(true)
        coEvery { getCategories.await() } returns listOf(category(1, "Action"))
        coEvery { api.categories() } returns listOf(
            RemoteCategory(5, "Drama"),
            RemoteCategory(0, "Default", isDefault = true),
        )
        coEvery { api.createCategory("Action") } returns RemoteCategory(6, "Action")
        coEvery { insertCategories.awaitOne(any()) } returns 2L
        serverLibrary()

        val summary = sync().await()

        coVerify { api.createCategory("Action") }
        coVerify(exactly = 0) { api.createCategory("Default") }
        val inserted = slot<Category>()
        coVerify { insertCategories.awaitOne(capture(inserted)) }
        assertEquals("Drama", inserted.captured.name)
        assertEquals(1, summary.categoriesCreatedLocal)
        assertEquals(1, summary.categoriesCreatedRemote)
    }

    private fun linkSetup(localNames: List<String>, remoteIds: Set<Int>) {
        preferences.syncCategories().set(true)
        coEvery { getCategories.await() } returns listOf(category(1, "Action"), category(2, "Drama"))
        coEvery { api.categories() } returns listOf(RemoteCategory(4, "Action"), RemoteCategory(5, "Drama"))
        coEvery { getCategories.awaitByMangaId(1L) } returns localNames.map { name ->
            if (name == "Action") category(1, name) else category(2, name)
        }
        serverLibrary(RemoteManga(100, "3", "/m", remoteIds, emptyList()))
    }

    @Test
    fun `without history category links are unioned both ways`() = runBlocking {
        linkSetup(localNames = listOf("Action"), remoteIds = setOf(5))

        sync().await()

        coVerify { api.updateMangaCategories(100, listOf(4), emptyList()) }
        coVerify { setMangaCategories.await(1L, match { it.toSet() == setOf(1L, 2L) }) }
    }

    @Test
    fun `a link removed locally since the last sync is removed on the server too`() = runBlocking {
        // Both sides had Action and Drama; the user dropped Action here.
        preferences.categoryLinksSnapshot().set("""{"3|/m":["action","drama"]}""")
        linkSetup(localNames = listOf("Drama"), remoteIds = setOf(4, 5))

        sync().await()

        coVerify { api.updateMangaCategories(100, emptyList(), listOf(4)) }
        coVerify(exactly = 0) { setMangaCategories.await(any(), any()) }
        assertTrue(preferences.categoryLinksSnapshot().get().contains("drama"))
        assertFalse(preferences.categoryLinksSnapshot().get().contains("action"))
    }

    @Test
    fun `a server without the history route does not stop the rest of the library sync`() = runBlocking {
        preferences.syncHistory().set(true)
        preferences.syncProgress().set(true)
        val c = chapter(10, "/c1")
        coEvery { getChapter.awaitAllRaw(1L, false) } returns listOf(c)
        coEvery { getHistory.awaitAllByMangaId(1L) } returns emptyList()
        // the server has the chapter read on the server, the app nothing
        serverLibrary(
            RemoteManga(7, "3", "/m", emptySet(), listOf(RemoteChapter(70, "/c1", isRead = false, lastPageRead = 0, lastReadAt = 0))),
        )
        // the app has history for it, so the sync wants to write it
        coEvery { getHistory.awaitAllByMangaId(1L) } returns listOf(
            eu.kanade.tachiyomi.data.database.models.History.create().apply { chapter_id = 10; last_read = 5_000_000 },
        )
        coEvery { api.putHistory(any()) } throws karasu.data.suwayomi.ServerApiException(404, "HTTP 404")

        val summary = sync().await()

        assertTrue(summary.errors.any { it.contains("history") }, "the user is told why history was not shared")
        coVerify(exactly = 1) { api.putHistory(any()) }
    }

    @Test
    fun `history is left alone when its switch is off`() = runBlocking {
        preferences.syncProgress().set(true)
        val c = chapter(10, "/c1")
        coEvery { getChapter.awaitAllRaw(1L, false) } returns listOf(c)
        serverLibrary(RemoteManga(7, "3", "/m", emptySet(), listOf(RemoteChapter(70, "/c1", lastReadAt = 99))))

        sync().await()

        coVerify(exactly = 0) { api.putHistory(any()) }
        coVerify(exactly = 0) { upsertHistory.await(any<Long>(), any(), any()) }
    }

    // region extension repositories

    private fun repo(baseUrl: String) = ExtensionRepo(baseUrl, "name", null, "https://site", "fingerprint")

    private fun repos(local: List<String>, remote: List<String>, snapshot: String? = null) {
        coEvery { getExtensionRepo.getAll() } returns local.map(::repo)
        coEvery { api.extensionStores() } returns remote
        coEvery { createExtensionRepo.await(any()) } returns CreateExtensionRepo.Result.Success
        if (snapshot != null) preferences.extensionRepoSnapshot().set(snapshot)
    }

    @Test
    fun `the first run unites the repositories and removes nothing`() = runBlocking {
        preferences.applyRemoteDeletions().set(true)
        preferences.pushLocalDeletions().set(true)
        repos(local = listOf("https://a/repo"), remote = listOf("https://b/repo/repo.json"))

        val summary = sync().await()

        coVerify(exactly = 1) { api.addExtensionStore("https://a/repo/repo.json") }
        coVerify(exactly = 1) { createExtensionRepo.await("https://b/repo/repo.json") }
        coVerify(exactly = 0) { api.removeExtensionStore(any()) }
        coVerify(exactly = 0) { deleteExtensionRepo.await(any()) }
        assertEquals(1, summary.reposPulled)
        assertEquals(1, summary.reposPushed)
        assertEquals("""["https://a/repo","https://b/repo"]""", preferences.extensionRepoSnapshot().get())
    }

    @Test
    fun `a repository removed in the app leaves the server only with pushLocalDeletions`() = runBlocking {
        repos(local = emptyList(), remote = listOf("https://a/repo/index.min.json"), snapshot = """["https://a/repo"]""")

        sync().await()
        coVerify(exactly = 0) { api.removeExtensionStore(any()) }
        coVerify(exactly = 0) { createExtensionRepo.await(any()) }

        preferences.pushLocalDeletions().set(true)
        val summary = sync().await()
        coVerify(exactly = 1) { api.removeExtensionStore("https://a/repo/index.min.json") }
        assertEquals(1, summary.reposRemovedRemote)
        assertEquals("[]", preferences.extensionRepoSnapshot().get())
    }

    @Test
    fun `a repository removed on the server leaves the app only with applyRemoteDeletions`() = runBlocking {
        repos(local = listOf("https://a/repo"), remote = emptyList(), snapshot = """["https://a/repo"]""")

        sync().await()
        coVerify(exactly = 0) { deleteExtensionRepo.await(any()) }
        coVerify(exactly = 0) { api.addExtensionStore(any()) }

        preferences.applyRemoteDeletions().set(true)
        val summary = sync().await()
        coVerify(exactly = 1) { deleteExtensionRepo.await("https://a/repo") }
        assertEquals(1, summary.reposRemovedLocal)
        assertEquals("[]", preferences.extensionRepoSnapshot().get())
    }

    @Test
    fun `index and repo json spellings are the same repository`() = runBlocking {
        repos(local = listOf("https://a/repo/index.min.json"), remote = listOf("https://a/repo/repo.json"))

        val summary = sync().await()

        coVerify(exactly = 0) { api.addExtensionStore(any()) }
        coVerify(exactly = 0) { createExtensionRepo.await(any()) }
        assertEquals(0, summary.reposPulled + summary.reposPushed)
    }

    @Test
    fun `a graphql failure is an error of its part and the others still ran`() = runBlocking {
        preferences.applyRemoteDeletions().set(true)
        preferences.lastDeletionId().set(7)
        coEvery { api.deletions(7) } returns listOf(DeletionEntry(9, "3", "/m", "/c1"))
        coEvery { api.extensionStores() } throws ServerApiException(200, "GraphQL: boom")

        val summary = sync().await()

        assertEquals(1, summary.errors.size)
        assertTrue(summary.errors.single().startsWith("extension repos"))
        assertEquals(9L, preferences.lastDeletionId().get())
    }

    // endregion
}
