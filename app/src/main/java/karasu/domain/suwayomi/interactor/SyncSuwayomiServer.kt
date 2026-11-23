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
import karasu.domain.history.interactor.GetHistory
import karasu.domain.history.interactor.UpsertHistory
import karasu.domain.manga.interactor.GetManga
import karasu.domain.manga.merged.MergedMangaRepository
import karasu.domain.manga.merged.interactor.MergedSources
import karasu.domain.suwayomi.ServerPreferences
import karasu.domain.suwayomi.SyncMath
import karasu.domain.suwayomi.SyncMath.MergedEntry
import karasu.domain.suwayomi.SyncMath.Progress
import karasu.domain.suwayomi.SyncMath.toEntries
import karasu.domain.suwayomi.models.DownloadPolicy
import karasu.domain.suwayomi.models.HistoryEntry
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
 *  3. Local deletions, which have to come after 2 so what was just removed because the server said
 *     so is not announced again as if it were this device's own doing.
 *  4. Merged sources, which are small and independent of the library walk.
 *  5. Categories, reading progress and history, in one pass over the server's library.
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
    private val getHistory: GetHistory,
    private val upsertHistory: UpsertHistory,
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
        val deletionsPushed: Int = 0,
        val historyPulled: Int = 0,
        val historyPushed: Int = 0,
        /** Parts the server answered with an error. Reaching the server at all is a thrown exception. */
        val errors: List<String> = emptyList(),
    ) {
        val isIdle: Boolean
            get() = categoriesCreatedLocal + categoriesCreatedRemote + categoryLinksLocal + categoryLinksRemote +
                chaptersPulled + chaptersPushed + mergedPulled + mergedPushed + deletionsApplied + deletionsPushed +
                historyPulled + historyPushed == 0
    }

    private var summary = Summary()

    suspend fun await(): Summary {
        if (!api.isEnabled) return Summary()
        summary = Summary()
        removedBecauseOfServer.clear()

        part("policy") { refreshPolicy() }
        if (preferences.applyRemoteDeletions().get()) part("deletions") { applyDeletions() }
        if (preferences.pushLocalDeletions().get()) part("local deletions") { pushLocalDeletions() }
        if (preferences.syncMergedSources().get()) part("merged sources") { syncMerged() }
        if (preferences.syncCategories().get() || preferences.syncProgress().get() || preferences.syncHistory().get()) {
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
    private suspend fun applyDeletions(skip: Set<String> = emptySet()) {
        val cursor = preferences.lastDeletionId().get()
        val entries = api.deletions(if (cursor < 0) 0 else cursor).sortedBy { it.id }
        if (entries.isEmpty()) {
            if (cursor < 0) preferences.lastDeletionId().set(0)
            return
        }
        if (cursor >= 0) {
            var applied = 0
            entries.forEach {
                val key = chapterKey(SyncMath.mangaKey(it.sourceId, it.mangaUrl), it.chapterUrl)
                if (key in skip) return@forEach
                if (deleteLocally(it.sourceId, it.mangaUrl, it.chapterUrl)) {
                    applied++
                    removedBecauseOfServer += key
                }
            }
            summary = summary.copy(deletionsApplied = summary.deletionsApplied + applied)
        }
        preferences.lastDeletionId().set(entries.maxOf { it.id })
    }

    /** Chapters this run deleted because the server said so; they are not this device's own deletions. */
    private val removedBecauseOfServer = mutableSetOf<String>()

    private fun chapterKey(mangaKey: String, chapterUrl: String) = "$mangaKey|$chapterUrl"

    private suspend fun deleteLocally(sourceId: String, mangaUrl: String, chapterUrl: String): Boolean {
        val source = SyncMath.parseSourceId(sourceId) ?: return false
        val manga = getManga.awaitByUrlAndSource(mangaUrl, source) ?: return false
        val mangaId = manga.id ?: return false
        val chapter = getChapter.awaitAllRaw(mangaId, false).firstOrNull { it.url == chapterUrl } ?: return false
        if (!downloadManager.isChapterDownloaded(chapter, manga, skipCache = true)) return false
        downloadManager.deleteChapters(listOf(chapter), manga, sourceManager.getOrStub(manga.source), force = true)
        return true
    }

    /**
     * Tells the server about chapters that were downloaded at the end of the last sync and are not
     * now, so the other devices drop them too.
     *
     * The first run only records what is downloaded: without a previous picture there is no way to
     * tell "deleted" from "never downloaded". Chapters this very run deleted because of the server
     * are left out (announcing them again would bounce one deletion between devices forever), and
     * so are the tombstones this device just created when it reads the log back — otherwise a
     * chapter downloaded again later would be deleted by its own old announcement.
     */
    private suspend fun pushLocalDeletions() {
        val current = downloadedNow()
        val raw = preferences.downloadedSnapshot().get()
        val previous = if (raw.isBlank()) null else decode(raw) { text ->
            json.decodeFromString(linkSnapshotSerializer, text).mapValues { (_, v) -> v.toSet() }
        }
        saveDownloadedSnapshot(current)
        if (previous == null) return

        val gone = mutableListOf<Triple<String, String, String>>() // source, manga url, chapter url
        val byKey = getManga.awaitFavorites().associateBy { SyncMath.mangaKey(it.source, it.url) }
        for ((mangaKey, urls) in previous) {
            val manga = byKey[mangaKey] ?: continue // left the library: not a deletion
            val still = current[mangaKey].orEmpty()
            urls.filter { it !in still && chapterKey(mangaKey, it) !in removedBecauseOfServer }
                .forEach { gone += Triple(manga.source.toString(), manga.url, it) }
        }
        if (gone.isEmpty()) return

        val ids = gone.mapNotNull { (source, mangaUrl, chapterUrl) ->
            SyncMath.parseSourceId(source)?.let { api.lookupChapter(it, mangaUrl, chapterUrl)?.chapterId }
        }
        if (ids.isEmpty()) return
        api.requestDeletions(ids, deleteFromServer = false)
        summary = summary.copy(deletionsPushed = ids.size)

        // Read our own announcements back so the cursor moves past them without applying them here.
        if (preferences.applyRemoteDeletions().get()) {
            applyDeletions(skip = gone.mapTo(mutableSetOf()) { (source, mangaUrl, chapterUrl) ->
                chapterKey(SyncMath.mangaKey(source, mangaUrl), chapterUrl)
            })
        }
    }

    /** `mangaKey -> urls of the chapters that are downloaded now`, for the library only. */
    private suspend fun downloadedNow(): Map<String, Set<String>> {
        val result = mutableMapOf<String, Set<String>>()
        for (manga in getManga.awaitFavorites()) {
            val mangaId = manga.id ?: continue
            val urls = getChapter.awaitAllRaw(mangaId, false)
                .filter { downloadManager.isChapterDownloaded(it, manga) }
                .mapTo(mutableSetOf()) { it.url }
            if (urls.isNotEmpty()) result[SyncMath.mangaKey(manga.source, manga.url)] = urls
        }
        return result
    }

    private fun saveDownloadedSnapshot(snapshot: Map<String, Set<String>>) {
        // "{}" and not "": an empty picture is still a picture, and "no picture yet" is the blank.
        preferences.downloadedSnapshot().set(
            json.encodeToString(linkSnapshotSerializer, snapshot.mapValues { (_, v) -> v.sorted() }),
        )
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
        var history = preferences.syncHistory().get()

        val local = getManga.awaitFavorites().filter { it.id != null }
            .associateBy { SyncMath.mangaKey(it.source, it.url) }
        val linkSnapshot = loadLinkSnapshot()
        val newLinkSnapshot = mutableMapOf<String, Set<String>>()
        val historySnapshot = loadHistorySnapshot()
        val newHistorySnapshot = mutableMapOf<String, Map<String, Long>>()

        var historyPulled = 0
        var historyPushed = 0
        var linksLocal = 0
        var linksRemote = 0
        var pulled = 0
        var pushed = 0

        api.forEachLibraryPage { page ->
            val remotePatches = mutableListOf<ServerSyncApi.ChapterPatch>()
            val historyPuts = mutableListOf<HistoryEntry>()
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
                if (history) {
                    val patched = remotePatches.mapTo(mutableSetOf()) { it.chapterId }
                    val (l, r, agreed) = reconcileHistory(manga, remote, historySnapshot[key].orEmpty(), patched, historyPuts)
                    historyPulled += l
                    historyPushed += r
                    if (agreed.isNotEmpty()) newHistorySnapshot[key] = agreed
                }
            }
            if (remotePatches.isNotEmpty()) api.updateChapters(remotePatches)
            // After the patches: changing the page read makes the server stamp the chapter with
            // "now", and the history entry has to be written over that, not under it.
            if (history && historyPuts.isNotEmpty()) {
                try {
                    api.putHistory(historyPuts)
                } catch (e: ServerApiException) {
                    // A server from before the history existed answers "no such route". The rest of
                    // the library (categories, progress) is not its fault and must still be done.
                    if (e.code != 404 && e.code != 405) throw e
                    history = false
                    summary = summary.copy(errors = summary.errors + "history: the server is too old to share it")
                }
            }
        }

        if (categories != null) saveLinkSnapshot(newLinkSnapshot)
        if (history) saveHistorySnapshot(newHistorySnapshot)
        summary = summary.copy(
            historyPulled = historyPulled,
            historyPushed = historyPushed,
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

    /**
     * Merges "when was this chapter last read" chapter by chapter (see [SyncMath.mergeHistory]).
     * Local changes are applied at once; server changes are queued in [puts] so a page goes out as
     * one request. A chapter whose read state is being patched is always written, because the patch
     * itself stamps it with the current time on the server.
     * @return entries changed locally, entries queued for the server, and the agreed values to remember.
     */
    private suspend fun reconcileHistory(
        manga: Manga,
        remote: RemoteManga,
        base: Map<String, Long>,
        patchedRemoteIds: Set<Int>,
        puts: MutableList<HistoryEntry>,
    ): Triple<Int, Int, Map<String, Long>> {
        val mangaId = manga.id!!
        val remoteByUrl = remote.chapters.associateBy { it.url }
        if (remoteByUrl.isEmpty()) return Triple(0, 0, emptyMap())

        val lastReadByChapterId = getHistory.awaitAllByMangaId(mangaId).associate { it.chapter_id to it.last_read }
        val agreed = mutableMapOf<String, Long>()
        var pulled = 0
        var pushed = 0
        for (chapter in getChapter.awaitAllRaw(mangaId, false)) {
            val remoteChapter = remoteByUrl[chapter.url] ?: continue
            val chapterId = chapter.id ?: continue
            val local = (lastReadByChapterId[chapterId] ?: 0L) / MILLIS
            val outcome = SyncMath.mergeHistory(base[chapter.url] ?: 0L, local, remoteChapter.lastReadAt)

            if (outcome.changeLocal) {
                // Nothing to write when removing a chapter that is not in the history here.
                if (outcome.result > 0 || local > 0) upsertHistory.await(chapterId, outcome.result * MILLIS, 0L)
                pulled++
            }
            if (outcome.changeRemote || remoteChapter.id in patchedRemoteIds) {
                puts += HistoryEntry(remoteChapter.id.toLong(), outcome.result)
                if (outcome.changeRemote) pushed++
            }
            if (outcome.result > 0) agreed[chapter.url] = outcome.result
        }
        return Triple(pulled, pushed, agreed)
    }

    private val historySnapshotSerializer =
        MapSerializer(String.serializer(), MapSerializer(String.serializer(), Long.serializer()))

    private fun loadHistorySnapshot(): Map<String, Map<String, Long>> =
        decode(preferences.historySnapshot().get()) { json.decodeFromString(historySnapshotSerializer, it) }

    private fun saveHistorySnapshot(snapshot: Map<String, Map<String, Long>>) {
        preferences.historySnapshot().set(json.encodeToString(historySnapshotSerializer, snapshot))
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

        /** Karasu keeps history times in milliseconds, the server in seconds. */
        const val MILLIS = 1000L
    }
}
