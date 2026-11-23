package karasu.domain.suwayomi.interactor

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.library.LibrarySort
import karasu.data.suwayomi.RemoteChapter
import karasu.data.suwayomi.RemoteManga
import karasu.data.suwayomi.ServerApiException
import karasu.data.suwayomi.ServerSyncApi
import karasu.domain.category.interactor.GetCategories
import karasu.domain.category.interactor.InsertCategories
import karasu.domain.category.interactor.SetMangaCategories
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.chapter.interactor.UpdateChapter
import karasu.domain.chapter.models.ChapterUpdate
import karasu.domain.manga.interactor.GetManga
import karasu.domain.manga.merged.MergedMangaRepository
import karasu.domain.manga.merged.interactor.MergedSources
import karasu.domain.suwayomi.ServerPreferences
import karasu.domain.suwayomi.SyncMath
import karasu.domain.suwayomi.SyncMath.MergedEntry
import karasu.domain.suwayomi.SyncMath.Progress
import karasu.domain.suwayomi.SyncMath.toEntries
import karasu.domain.suwayomi.models.DownloadPolicy
import karasu.domain.suwayomi.models.MangaRef
import karasu.domain.suwayomi.models.MergedSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Reconciles this device with a Suwayomi-Server. The whole sync is driven from here: the server
 * stores facts and answers questions, it never pushes and never knows this device exists.
 *
 * Order matters and is deliberate:
 *  1. The download policy, which doubles as the first request — if nothing answers there is no
 *     point walking the library, so [java.io.IOException] ends the run for a retry.
 *  2. Remote deletions, so a chapter the server was told to drop is not uploaded back as "read".
 *  3. Merged sources, which are small and independent of the library walk.
 *  4. Categories and reading progress, in one pass over the server's library.
 *
 * Works are matched by `(source, url)` on both sides, never by id. A failure of one part that the
 * server *answered* (an error status, a GraphQL error) is recorded and the next part still runs;
 * a failure to reach the server aborts, since every later part would fail the same way.
 */
class SyncSuwayomiServer(
    private val api: ServerSyncApi,
    private val preferences: ServerPreferences,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
    private val updateChapter: UpdateChapter,
    private val getCategories: GetCategories,
    private val insertCategories: InsertCategories,
    private val setMangaCategories: SetMangaCategories,
    private val mergedSources: MergedSources,
    private val mergedRepository: MergedMangaRepository,
    private val downloadManager: DownloadManager,
    private val sourceManager: SourceManager,
) {

    data class Summary(
        val categoriesCreatedLocal: Int = 0,
        val categoriesCreatedRemote: Int = 0,
        val categoryLinksLocal: Int = 0,
        val categoryLinksRemote: Int = 0,
        val chaptersPulled: Int = 0,
        val chaptersPushed: Int = 0,
        val mergedPulled: Int = 0,
        val mergedPushed: Int = 0,
        val deletionsApplied: Int = 0,
        /** Parts the server answered with an error. Reaching the server at all is a thrown exception. */
        val errors: List<String> = emptyList(),
    ) {
        val isIdle: Boolean
            get() = categoriesCreatedLocal + categoriesCreatedRemote + categoryLinksLocal + categoryLinksRemote +
                chaptersPulled + chaptersPushed + mergedPulled + mergedPushed + deletionsApplied == 0
    }

    private var summary = Summary()

    suspend fun await(): Summary {
        if (!api.isEnabled) return Summary()
        summary = Summary()

        part("policy") { refreshPolicy() }
        if (preferences.applyRemoteDeletions().get()) part("deletions") { applyDeletions() }
        if (preferences.syncMergedSources().get()) part("merged sources") { syncMerged() }
        if (preferences.syncCategories().get() || preferences.syncProgress().get()) {
            part("library") { syncLibrary() }
        }
        return summary
    }

    /**
     * Runs one part. An answer from the server that says "no" is the part's own problem and is
     * kept for the report; anything else — no connection, cancellation — is not, and propagates.
     */
    private suspend fun part(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ServerApiException) {
            Logger.w(e) { "Suwayomi sync: $name failed" }
            summary = summary.copy(errors = summary.errors + "$name: ${e.message}")
        }
    }

    // region policy

    private suspend fun refreshPolicy() {
        val policy = api.downloadPolicy()
        preferences.serverDownloadPolicy().set(policy.name)
    }

    // endregion

    // region deletions

    /**
     * Deletes locally the chapters the server was asked to delete.
     *
     * The first run only moves the cursor to the end. Replaying the server's entire history on a
     * device that has just been set up would delete downloads the user has today because of
     * something they did last year.
     */
    private suspend fun applyDeletions() {
        val cursor = preferences.lastDeletionId().get()
        val entries = api.deletions(if (cursor < 0) 0 else cursor).sortedBy { it.id }
        if (entries.isEmpty()) {
            if (cursor < 0) preferences.lastDeletionId().set(0)
            return
        }
        if (cursor >= 0) {
            var applied = 0
            entries.forEach { if (deleteLocally(it.sourceId, it.mangaUrl, it.chapterUrl)) applied++ }
            summary = summary.copy(deletionsApplied = applied)
        }
        preferences.lastDeletionId().set(entries.maxOf { it.id })
    }

    private suspend fun deleteLocally(sourceId: String, mangaUrl: String, chapterUrl: String): Boolean {
        val source = SyncMath.parseSourceId(sourceId) ?: return false
        val manga = getManga.awaitByUrlAndSource(mangaUrl, source) ?: return false
        val mangaId = manga.id ?: return false
        val chapter = getChapter.awaitAllRaw(mangaId, false).firstOrNull { it.url == chapterUrl } ?: return false
        if (!downloadManager.isChapterDownloaded(chapter, manga, skipCache = true)) return false
        downloadManager.deleteChapters(listOf(chapter), manga, sourceManager.getOrStub(manga.source), force = true)
        return true
    }

    // endregion

    // region merged sources

    @Serializable
    private data class SnapshotEntry(val sourceId: String, val url: String, val updatesEnabled: Boolean = true)

    private val mergedSnapshotSerializer =
        MapSerializer(String.serializer(), ListSerializer(SnapshotEntry.serializer()))

    private fun loadMergedSnapshot(): Map<String, List<MergedEntry>> = decode(preferences.mergedSnapshot().get()) {
        json.decodeFromString(mergedSnapshotSerializer, it).mapValues { (_, list) ->
            list.map { e -> MergedEntry(e.sourceId, e.url, e.updatesEnabled) }
        }
    }

    private fun saveMergedSnapshot(snapshot: Map<String, List<MergedEntry>>) {
        val raw = snapshot.mapValues { (_, list) -> list.map { SnapshotEntry(it.sourceId, it.url, it.updatesEnabled) } }
        preferences.mergedSnapshot().set(json.encodeToString(mergedSnapshotSerializer, raw))
    }

    private suspend fun syncMerged() {
        val favorites = getManga.awaitFavorites().filter { it.id != null }
        val byKey = favorites.associateBy { SyncMath.mangaKey(it.source, it.url) }
        val withMerges = mergedRepository.getMangaIdsWithMerges()
        val remoteGroups = api.mergedGroups().associateBy { SyncMath.mangaKey(it.primary.sourceId, it.primary.url) }

        val snapshot = loadMergedSnapshot()
        val newSnapshot = mutableMapOf<String, List<MergedEntry>>()
        var pulled = 0
        var pushed = 0

        // Only works both sides know about: a group whose primary is not in this library has
        // nothing local to attach to, and a local merge the server has no work for is reported by
        // the PUT itself (404) and left alone.
        val keys = byKey.filter { (key, manga) -> manga.id in withMerges || key in remoteGroups }.keys
        for (key in keys) {
            val manga = byKey.getValue(key)
            val mangaId = manga.id ?: continue
            val local = mergedSources.await(mangaId).map {
                MergedSource(it.source.toString(), it.url, it.priority, it.updatesEnabled)
            }.toEntries()
            val remote = remoteGroups[key]?.sources.orEmpty().toEntries()
                .filterNot { it.sourceId == manga.source.toString() }
            val outcome = SyncMath.mergeMerged(snapshot[key], local, remote)

            if (outcome.changeRemote) {
                val sources = outcome.result.mapIndexed { rank, e ->
                    MergedSource(e.sourceId, e.url, rank, e.updatesEnabled)
                }
                val group = api.putMerged(MangaRef(manga.source.toString(), manga.url), sources)
                if (group == null) {
                    Logger.i { "Suwayomi sync: server has no work for ${manga.title}, merge not pushed" }
                    continue
                }
                pushed++
            }
            if (outcome.changeLocal) {
                applyMergedLocally(mangaId, manga.source, outcome.result)
                pulled++
            }
            if (outcome.result.isNotEmpty()) newSnapshot[key] = outcome.result
        }
        saveMergedSnapshot(newSnapshot)
        summary = summary.copy(mergedPulled = pulled, mergedPushed = pushed)
    }

    private suspend fun applyMergedLocally(mangaId: Long, ownSource: Long, desired: List<MergedEntry>) {
        val current = mergedSources.await(mangaId)
        val wanted = desired.mapNotNull { e ->
            SyncMath.parseSourceId(e.sourceId)?.takeIf { it != ownSource }?.let { it to e }
        }
        val wantedKeys = wanted.mapTo(mutableSetOf()) { (id, e) -> "$id|${e.url}" }

        // Removals first: the table is unique on (manga, source), so a source whose url changed has
        // to leave before it can come back with the new one.
        current.filter { "${it.source}|${it.url}" !in wantedKeys }.forEach { mergedSources.remove(mangaId, it.source) }

        val remaining = current.filter { "${it.source}|${it.url}" in wantedKeys }.associateBy { it.source }
        wanted.forEachIndexed { rank, (sourceId, entry) ->
            // Karasu numbers merged priorities from 1; the primary manga itself is implicit.
            val priority = rank + 1
            val existing = remaining[sourceId]
            if (existing == null) {
                mergedSources.add(mangaId, sourceId, entry.url, priority)
                if (!entry.updatesEnabled) mergedSources.setReserve(mangaId, sourceId, true)
            } else {
                if (existing.priority != priority) mergedSources.reorder(mangaId, sourceId, priority)
                if (existing.updatesEnabled != entry.updatesEnabled) {
                    mergedSources.setReserve(mangaId, sourceId, !entry.updatesEnabled)
                }
            }
        }
    }

    // endregion

    // region categories and progress

    /** The server-side category the local one corresponds to, by normalised name. */
    private class CategoryMap(
        val localIdByKey: Map<String, Long>,
        val remoteIdByKey: Map<String, Int>,
    ) {
        val keyByRemoteId = remoteIdByKey.entries.associate { (k, v) -> v to k }
    }

    private suspend fun syncLibrary() {
        val categories = if (preferences.syncCategories().get()) reconcileCategoryNames() else null
        val progress = preferences.syncProgress().get()

        val local = getManga.awaitFavorites().filter { it.id != null }
            .associateBy { SyncMath.mangaKey(it.source, it.url) }
        val linkSnapshot = loadLinkSnapshot()
        val newLinkSnapshot = mutableMapOf<String, Set<String>>()

        var linksLocal = 0
        var linksRemote = 0
        var pulled = 0
        var pushed = 0

        api.forEachLibraryPage { page ->
            val remotePatches = mutableListOf<ServerSyncApi.ChapterPatch>()
            for (remote in page) {
                val key = SyncMath.mangaKey(remote.sourceId, remote.url)
                val manga = local[key] ?: continue
                if (categories != null) {
                    val (l, r, names) = reconcileLinks(manga, remote, categories, linkSnapshot[key].orEmpty())
                    linksLocal += l
                    linksRemote += r
                    newLinkSnapshot[key] = names
                }
                if (progress) {
                    val (p, q) = reconcileProgress(manga, remote, remotePatches)
                    pulled += p
                    pushed += q
                }
            }
            if (remotePatches.isNotEmpty()) api.updateChapters(remotePatches)
        }

        if (categories != null) saveLinkSnapshot(newLinkSnapshot)
        summary = summary.copy(
            categoryLinksLocal = linksLocal,
            categoryLinksRemote = linksRemote,
            chaptersPulled = pulled,
            chaptersPushed = pushed,
        )
    }

    /**
     * Makes both sides know the same category names, creating what is missing wherever it is.
     * Names are the identity: ids differ on each side, and a rename looks like a new category.
     * Deleting a category never propagates — it would take the manga links with it.
     */
    private suspend fun reconcileCategoryNames(): CategoryMap {
        val localCategories = getCategories.await().filter { it.isSyncable() }
        val remoteCategories = api.categories().filter { !it.isDefault && it.id != 0 }

        val localByKey = localCategories.associateBy { SyncMath.categoryKey(it.name) }.toMutableMap()
        val remoteByKey = remoteCategories.associateBy { SyncMath.categoryKey(it.name) }.toMutableMap()

        var createdLocal = 0
        var createdRemote = 0

        (remoteByKey.keys - localByKey.keys).forEach { key ->
            val remote = remoteByKey.getValue(key)
            val category = Category.create(remote.name.trim()).apply {
                order = (localByKey.values.maxOfOrNull { it.order } ?: 0) + 1
                mangaSort = LibrarySort.Title.categoryValue
            }
            val id = insertCategories.awaitOne(category) ?: return@forEach
            category.id = id.toInt()
            localByKey[key] = category
            createdLocal++
        }
        for (key in localByKey.keys - remoteByKey.keys) {
            val local = localByKey.getValue(key)
            remoteByKey[key] = api.createCategory(local.name.trim())
            createdRemote++
        }

        summary = summary.copy(categoriesCreatedLocal = createdLocal, categoriesCreatedRemote = createdRemote)
        return CategoryMap(
            localIdByKey = localByKey.mapValues { (_, c) -> c.id!!.toLong() },
            remoteIdByKey = remoteByKey.mapValues { (_, c) -> c.id },
        )
    }

    /** Rule-based categories fill themselves from rules, and the system ones are not rows at all. */
    private fun Category.isSyncable() = !isSystem && rule == null && id != null && (id ?: 0) > 0

    /**
     * Brings one manga's category links in line on both sides.
     * @return links changed locally, links changed remotely, and the agreed set of category keys.
     */
    private suspend fun reconcileLinks(
        manga: Manga,
        remote: RemoteManga,
        map: CategoryMap,
        base: Set<String>,
    ): Triple<Int, Int, Set<String>> {
        val mangaId = manga.id!!
        val current = getCategories.awaitByMangaId(mangaId)
        val localKeys = current.filter { it.isSyncable() }.mapTo(mutableSetOf()) { SyncMath.categoryKey(it.name) }
        val remoteKeys = remote.categoryIds.mapNotNullTo(mutableSetOf()) { map.keyByRemoteId[it] }

        val merged = SyncMath.mergeSets(base, localKeys, remoteKeys)
            // A category that exists on neither side after reconcile cannot be linked.
            .filterTo(mutableSetOf()) { it in map.localIdByKey && it in map.remoteIdByKey }

        var changedLocal = 0
        if (merged != localKeys) {
            // setCategories replaces every link, so what this sync does not manage (rule-based
            // categories) is carried over explicitly.
            val untouched = current.filterNot { it.isSyncable() }.mapNotNull { it.id?.toLong() }
            setMangaCategories.await(mangaId, untouched + merged.map { map.localIdByKey.getValue(it) })
            changedLocal = (merged - localKeys).size + (localKeys - merged).size
        }
        var changedRemote = 0
        if (merged != remoteKeys) {
            val add = (merged - remoteKeys).map { map.remoteIdByKey.getValue(it) }
            val remove = (remoteKeys - merged).map { map.remoteIdByKey.getValue(it) }
            api.updateMangaCategories(remote.id, add, remove)
            changedRemote = add.size + remove.size
        }
        return Triple(changedLocal, changedRemote, merged)
    }

    /**
     * Merges read state chapter by chapter, matched by chapter url inside the manga.
     * Local changes are applied immediately; server changes are queued in [patches] so the whole
     * page goes out as a few requests.
     * @return chapters changed locally and chapters queued for the server.
     */
    private suspend fun reconcileProgress(
        manga: Manga,
        remote: RemoteManga,
        patches: MutableList<ServerSyncApi.ChapterPatch>,
    ): Pair<Int, Int> {
        val mangaId = manga.id!!
        val remoteByUrl = remote.chapters.associateBy { it.url }
        if (remoteByUrl.isEmpty()) return 0 to 0

        val updates = mutableListOf<ChapterUpdate>()
        var queued = 0
        for (chapter in getChapter.awaitAllRaw(mangaId, false)) {
            val remoteChapter = remoteByUrl[chapter.url] ?: continue
            val chapterId = chapter.id ?: continue
            val localState = Progress(chapter.read, chapter.last_page_read)
            val remoteState = remoteChapter.toProgress()
            val merged = SyncMath.mergeProgress(localState, remoteState)

            if (merged != localState) {
                val pageChanged = merged.lastPageRead != localState.lastPageRead
                updates += ChapterUpdate(
                    id = chapterId,
                    read = merged.read.takeIf { it != localState.read },
                    lastPageRead = merged.lastPageRead.toLong().takeIf { pageChanged },
                )
            }
            if (merged != remoteState) {
                patches += ServerSyncApi.ChapterPatch(remoteChapter.id, merged.read, merged.lastPageRead)
                queued++
            }
        }
        if (updates.isNotEmpty()) updateChapter.awaitAll(updates)
        return updates.size to queued
    }

    private fun RemoteChapter.toProgress() = Progress(isRead, lastPageRead)

    private fun loadLinkSnapshot(): Map<String, Set<String>> = decode(preferences.categoryLinksSnapshot().get()) {
        json.decodeFromString(linkSnapshotSerializer, it).mapValues { (_, v) -> v.toSet() }
    }

    private fun saveLinkSnapshot(snapshot: Map<String, Set<String>>) {
        val raw = snapshot.filterValues { it.isNotEmpty() }.mapValues { (_, v) -> v.sorted() }
        preferences.categoryLinksSnapshot().set(json.encodeToString(linkSnapshotSerializer, raw))
    }

    // endregion

    /** A snapshot that cannot be read is treated as absent: the next sync is a union, which loses nothing. */
    private inline fun <T> decode(raw: String, parse: (String) -> Map<String, T>): Map<String, T> {
        if (raw.isBlank()) return emptyMap()
        return try {
            parse(raw)
        } catch (e: Exception) {
            Logger.w(e) { "Ignoring unreadable Suwayomi sync snapshot" }
            emptyMap()
        }
    }

    private val linkSnapshotSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    /** The policy the server last reported, for callers that only need to read it. */
    fun cachedPolicy(): DownloadPolicy = DownloadPolicy.parse(preferences.serverDownloadPolicy().get())

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
