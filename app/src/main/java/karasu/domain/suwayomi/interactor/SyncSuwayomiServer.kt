package karasu.domain.suwayomi.interactor

import android.app.Application
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.extension.ExtensionInstallerJob
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.library.LibrarySort
import karasu.data.suwayomi.RemoteChapter
import karasu.data.suwayomi.RemoteManga
import karasu.data.suwayomi.ServerApiException
import karasu.data.suwayomi.ServerSyncApi
import karasu.domain.category.interactor.GetCategories
import karasu.domain.category.interactor.InsertCategories
import karasu.domain.category.interactor.SetMangaCategories
import karasu.domain.category.interactor.UpdateCategories
import karasu.domain.category.models.CategoryUpdate
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.chapter.interactor.InsertChapter
import karasu.domain.chapter.interactor.UpdateChapter
import karasu.domain.chapter.models.ChapterUpdate
import karasu.domain.extension.repo.interactor.CreateExtensionRepo
import karasu.domain.extension.repo.interactor.DeleteExtensionRepo
import karasu.domain.extension.repo.interactor.GetExtensionRepo
import karasu.domain.history.interactor.GetHistory
import karasu.domain.history.interactor.UpsertHistory
import karasu.domain.operations.OpAction
import karasu.domain.operations.OpEntity
import karasu.domain.operations.OperationLog
import karasu.domain.operations.SyncOrigin
import karasu.domain.manga.interactor.GetManga
import karasu.domain.manga.interactor.InsertManga
import karasu.domain.manga.interactor.UpdateManga
import karasu.domain.manga.models.MangaUpdate
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
import karasu.domain.suwayomi.models.PlannedRemoval
import karasu.domain.suwayomi.models.RemovalKind
import karasu.domain.suwayomi.models.RemovalSide
import karasu.domain.suwayomi.models.ServerOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    private val updateCategories: UpdateCategories,
    private val operations: OperationLog,
    private val getExtensionRepo: GetExtensionRepo,
    private val createExtensionRepo: CreateExtensionRepo,
    private val deleteExtensionRepo: DeleteExtensionRepo,
    private val insertManga: InsertManga,
    private val insertChapter: InsertChapter,
    private val updateManga: UpdateManga,
    private val coverCache: CoverCache,
    private val extensionManager: ExtensionManager,
    private val app: Application,
) {

    data class Summary(
        val categoriesCreatedLocal: Int = 0,
        val categoriesCreatedRemote: Int = 0,
        val categoriesRenamedLocal: Int = 0,
        val categoriesRenamedRemote: Int = 0,
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
        val reposPulled: Int = 0,
        val reposPushed: Int = 0,
        val reposRemovedLocal: Int = 0,
        val reposRemovedRemote: Int = 0,
        /** The server's log no longer reached back to this device, so this run reconciled by state alone. */
        val logReset: Boolean = false,
        /** Removals found and not done, waiting for the user's approval (see [SyncSuwayomiServer.await]). */
        val heldRemovals: List<PlannedRemoval> = emptyList(),
        /** Parts the server answered with an error. Reaching the server at all is a thrown exception. */
        val errors: List<String> = emptyList(),
    ) {
        val isIdle: Boolean
            get() = categoriesCreatedLocal + categoriesCreatedRemote + categoriesRenamedLocal + categoriesRenamedRemote + categoryLinksLocal + categoryLinksRemote +
                chaptersPulled + chaptersPushed + mergedPulled + mergedPushed + deletionsApplied + deletionsPushed +
                historyPulled + historyPushed + reposPulled + reposPushed + reposRemovedLocal + reposRemovedRemote + heldRemovals.size == 0
    }

    private var summary = Summary()

    // region removals the user has to approve

    /** The keys of the removals this run may do; null lets every removal through. */
    private var approvedRemovals: Set<String>? = null

    private val held = mutableListOf<PlannedRemoval>()

    /**
     * Asks whether a removal may be done now. One that has not been approved is remembered in [held] and the caller must
     * leave things as they are, including the snapshot it keeps, so the next run finds the same removal again.
     */
    private fun allowRemoval(kind: RemovalKind, side: RemovalSide, key: String, label: String): Boolean {
        val approved = approvedRemovals ?: return true
        if (key in approved) return true
        if (held.none { it.key == key }) held += PlannedRemoval(kind, side, key, label)
        return false
    }

    private fun anyHeld() = held.isNotEmpty()

    // endregion

    /**
     * Runs the sync. [approvedRemovals] says which removals this run may do: null lets every removal through, and a set
     * lets through only the removals with those keys. Any other removal is not done: it is listed in
     * [Summary.heldRemovals], and everything the run would have remembered about it (the snapshots, the log cursor and
     * the acknowledgement) is left as it was, so the next run finds the very same removal again.
     */
    suspend fun await(approvedRemovals: Set<String>? = null): Summary {
        if (!api.isEnabled) return Summary()
        // Everything this run writes locally is somebody else's change arriving, not the user's own.
        return withContext(SyncOrigin()) { run(approvedRemovals) }
    }

    private suspend fun run(approved: Set<String>?): Summary {
        summary = Summary()
        approvedRemovals = approved
        held.clear()
        val cursorBefore = preferences.lastOperationSeq().get()
        removedBecauseOfServer.clear()
        remoteLatest.clear()
        remoteAction.clear()
        serverAcknowledges = false
        opsAvailable = false
        clockOffset = 0

        part("policy") { refreshPolicy() }
        part("operation log") { pullOperations() }
        if (preferences.applyRemoteDeletions().get()) part("deletions") { applyDeletions() }
        if (preferences.pushLocalDeletions().get()) part("local deletions") { pushLocalDeletions() }
        if (preferences.syncMergedSources().get()) part("merged sources") { syncMerged() }
        if (preferences.syncCategories().get() || preferences.syncProgress().get() || preferences.syncHistory().get()) {
            part("library") { syncLibrary() }
        }
        part("extension repos") { syncExtensionRepos() }
        part("extensions") { syncExtensions() }
        part("operation log") { publishOperations() }
        if (anyHeld()) {
            // Something this run read is still to be acted on: read it again next time, and do not say it was applied.
            preferences.lastOperationSeq().set(cursorBefore)
        } else if (opsAvailable && summary.errors.isEmpty()) {
            // Everything this run read has been applied: only now may the server forget it.
            preferences.operationAckSeq().set(pulledSeq)
        }
        summary = summary.copy(heldRemovals = held.toList())
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

    // region operation log

    /** Latest change per key made by someone else (the web, another device), in the server's clock. */
    private val remoteLatest = mutableMapOf<String, Long>()

    /** What that latest change was (SET or REMOVE), per key: the log says what happened, not just when. */
    private val remoteAction = mutableMapOf<String, String>()

    /** True when the server speaks log version 3: it acknowledges, compacts and understands gzip. */
    private var serverAcknowledges = false

    /**
     * Where this device's cursor stood when the run began. At 0 the first page is the server's whole retained history,
     * which says nothing about what happened *after this device last looked*; above 0 every operation read is news.
     */
    private var cursorAtStart = 0L

    /** The highest server sequence read in this run; it becomes the acknowledgement once the run ends without errors. */
    private var pulledSeq = 0L

    /** False for a server from before the log existed: conflicts then fall back to the older rules. */
    private var opsAvailable = false

    /** The server's clock minus this device's, so what this device did is dated in a time both agree on. */
    private var clockOffset = 0L

    private enum class Side { LOCAL, REMOTE }

    /**
     * Reads what the others did since the last run. This is what settles a conflict — both sides
     * changed the same thing — by the time each change was *made*, instead of by a rule that prefers
     * one kind of change. Only conflicts consult it; the state comparison still does the rest.
     */
    private suspend fun pullOperations() {
        val mine = preferences.ensureDeviceId()
        var cursor = preferences.lastOperationSeq().get()
        cursorAtStart = cursor
        var resetting = false
        val ack = preferences.operationAckSeq().get()
        try {
            while (true) {
                // the acknowledgement rides on the first page only: it is about the previous run, not this one
                val page = api.operations(cursor, OPERATIONS_PAGE, ack = if (serverAcknowledges) null else ack.takeIf { it > 0 })
                opsAvailable = true
                if (page.minSeq != null) serverAcknowledges = true
                if (page.serverTime > 0) clockOffset = page.serverTime - operations.now()
                if (page.resetRequired && !resetting) {
                    // The server dropped operations this device never saw: the log cannot tell what happened, so the
                    // state decides. With nothing agreed on, the sync joins both sides and removes nothing. The pages
                    // are still read to the end: the server keeps the warning until the acknowledgement reaches its head.
                    Logger.w { "Suwayomi sync: the server's log no longer reaches back far enough, reconciling by state" }
                    resetting = true
                    cursorAtStart = 0L
                    preferences.clearAgreedState()
                    summary = summary.copy(logReset = true)
                }
                page.operations.filter { it.deviceId != mine }.forEach {
                    if (remoteLatest[it.key]?.let { known -> it.ts >= known } != false) {
                        remoteLatest[it.key] = it.ts
                        remoteAction[it.key] = it.action
                    }
                }
                if (page.operations.size < OPERATIONS_PAGE || page.lastSeq <= cursor) {
                    cursor = maxOf(cursor, page.lastSeq)
                    break
                }
                cursor = page.lastSeq
            }
            pulledSeq = cursor
            preferences.lastOperationSeq().set(cursor)
        } catch (e: ServerApiException) {
            if (e.code != 404 && e.code != 405) throw e // an old server simply has no log
        }
    }

    /**
     * Who made the change that counts, or null when the log cannot say (no entry on one side, or a
     * server without a log) and the caller must use its own rule. [localTs] is this device's clock;
     * a tie goes to the server, which is the one clock all devices share.
     */
    private fun winner(remoteKey: String, localTs: Long?): Side? {
        if (!opsAvailable || localTs == null) return null
        val remote = remoteLatest[remoteKey] ?: return null
        return if (localTs + clockOffset > remote) Side.LOCAL else Side.REMOTE
    }

    private fun progressKey(manga: Manga, chapterUrl: String) = "progress:${manga.source}|${manga.url}|$chapterUrl"

    private fun historyKey(manga: Manga, chapterUrl: String) = "history:${manga.source}|${manga.url}|$chapterUrl"

    /**
     * Tells the server what this device's user did, dated when they did it, so the other devices can
     * settle their own conflicts against it. An operation that points at something that no longer
     * exists is dropped: there is nothing left for it to describe.
     */
    private suspend fun publishOperations() {
        if (!opsAvailable) return
        // A removal this device did and is still waiting to be approved is not announced: the others would act on it.
        val pending = operations.pending().filter { !anyHeld() || it.action != OpAction.REMOVE }
        if (pending.isEmpty()) return
        val device = preferences.ensureDeviceId()
        val categoryMap = loadCategoryMap()
        val out = pending.mapNotNull { describe(it, device, categoryMap) }
        api.publishOperations(out, gzip = serverAcknowledges)
        // the server has them now, and its log is the record from here on
        operations.remove(pending.map { it.id })
        operations.prune()
    }

    private suspend fun describe(
        op: karasu.domain.operations.LocalOp,
        device: String,
        categoryMap: Map<Long, MappedCategory>,
    ): ServerOperation? {
        fun build(key: String, value: JsonElement?) =
            ServerOperation(op.id, device, op.ts + clockOffset, op.entity, key, op.action, value)

        val chapter = op.chapterId?.let { getChapter.awaitById(it) }
        val mangaId = op.mangaId ?: chapter?.manga_id
        val manga = mangaId?.let { getManga.awaitById(it) }
        return when (op.entity) {
            OpEntity.PROGRESS -> {
                if (chapter == null || manga == null) return null
                build(
                    progressKey(manga, chapter.url),
                    buildJsonObject { put("read", chapter.read); put("lastPageRead", chapter.last_page_read) },
                )
            }
            OpEntity.HISTORY -> {
                if (chapter == null || manga == null) return null
                val lastRead = getHistory.awaitAllByMangaId(manga.id!!).firstOrNull { it.chapter_id == chapter.id }?.last_read ?: 0L
                build(
                    historyKey(manga, chapter.url),
                    if (op.action == OpAction.REMOVE) null else buildJsonObject { put("lastReadAt", lastRead / MILLIS) },
                )
            }
            OpEntity.CATEGORY -> {
                val remoteId = categoryMap[op.categoryId]?.remoteId ?: return null
                val name = getCategories.await().firstOrNull { it.id?.toLong() == op.categoryId }?.name ?: op.name
                build("category:$remoteId", if (op.action == OpAction.REMOVE) null else buildJsonObject { put("name", name ?: "") })
            }
            OpEntity.CATEGORY_LINK -> {
                val remoteId = categoryMap[op.categoryId]?.remoteId ?: return null
                if (manga == null) return null
                build("link:${manga.source}|${manga.url}|$remoteId", null)
            }
            OpEntity.MERGED -> {
                if (manga == null) return null
                val sources = mergedSources.await(manga.id!!)
                build(
                    "merged:${manga.source}|${manga.url}",
                    buildJsonObject {
                        put(
                            "sources",
                            JsonArray(
                                sources.map {
                                    buildJsonObject {
                                        put("sourceId", it.source.toString())
                                        put("url", it.url)
                                        put("priority", it.priority)
                                        put("updatesEnabled", it.updatesEnabled)
                                    }
                                },
                            ),
                        )
                    },
                )
            }
            OpEntity.EXTENSION_REPO -> {
                val indexUrl = "${op.name ?: return null}/repo.json"
                build("repo:$indexUrl", if (op.action == OpAction.REMOVE) null else buildJsonObject { put("indexUrl", indexUrl) })
            }
            else -> null
        }
    }

    // endregion

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
        var heldHere = false
        if (cursor >= 0) {
            var applied = 0
            entries.forEach {
                val key = chapterKey(SyncMath.mangaKey(it.sourceId, it.mangaUrl), it.chapterUrl)
                if (key in skip) return@forEach
                // Only a chapter that is downloaded here is something to remove: asking about the others would be noise.
                if (!isDownloadedLocally(it.sourceId, it.mangaUrl, it.chapterUrl)) return@forEach
                if (!allowRemoval(
                        RemovalKind.DOWNLOADED_CHAPTER, RemovalSide.THIS_DEVICE, "delete-here:$key",
                        "${chapterLabel(it.sourceId, it.mangaUrl, it.chapterUrl)}: downloaded copy on this device",
                    )
                ) {
                    heldHere = true
                    return@forEach
                }
                if (deleteLocally(it.sourceId, it.mangaUrl, it.chapterUrl)) {
                    applied++
                    removedBecauseOfServer += key
                }
            }
            summary = summary.copy(deletionsApplied = summary.deletionsApplied + applied)
        }
        // With one still waiting, the cursor stays: the others are read again next time and found already done.
        if (!heldHere) preferences.lastDeletionId().set(entries.maxOf { it.id })
    }

    /** Chapters this run deleted because the server said so; they are not this device's own deletions. */
    private val removedBecauseOfServer = mutableSetOf<String>()

    private fun chapterKey(mangaKey: String, chapterUrl: String) = "$mangaKey|$chapterUrl"

    private suspend fun isDownloadedLocally(sourceId: String, mangaUrl: String, chapterUrl: String): Boolean {
        val source = SyncMath.parseSourceId(sourceId) ?: return false
        val manga = getManga.awaitByUrlAndSource(mangaUrl, source) ?: return false
        val mangaId = manga.id ?: return false
        val chapter = getChapter.awaitAllRaw(mangaId, false).firstOrNull { it.url == chapterUrl } ?: return false
        return downloadManager.isChapterDownloaded(chapter, manga, skipCache = true)
    }

    /** `Title - Chapter name`, as far as this device knows them; the urls when it does not. */
    private suspend fun chapterLabel(sourceId: String, mangaUrl: String, chapterUrl: String): String {
        val source = SyncMath.parseSourceId(sourceId)
        val manga = source?.let { getManga.awaitByUrlAndSource(mangaUrl, it) }
        val chapter = manga?.id?.let { id -> getChapter.awaitAllRaw(id, false).firstOrNull { it.url == chapterUrl } }
        return "${manga?.safeTitle() ?: mangaUrl} - ${chapter?.safeName() ?: chapterUrl}"
    }

    // A label is only for the confirmation and must never be the reason a sync fails: the name or title may not be set.
    private fun Chapter.safeName(): String = runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() } ?: url

    private fun Manga.safeTitle(): String = runCatching { title }.getOrNull()?.takeIf { it.isNotBlank() } ?: url

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
        if (previous == null) {
            saveDownloadedSnapshot(current)
            return
        }

        val gone = mutableListOf<Triple<String, String, String>>() // source, manga url, chapter url
        val byKey = getManga.awaitFavorites().associateBy { SyncMath.mangaKey(it.source, it.url) }
        for ((mangaKey, urls) in previous) {
            val manga = byKey[mangaKey] ?: continue // left the library: not a deletion
            val still = current[mangaKey].orEmpty()
            urls.filter { it !in still && chapterKey(mangaKey, it) !in removedBecauseOfServer }
                .forEach { gone += Triple(manga.source.toString(), manga.url, it) }
        }
        if (gone.isEmpty()) {
            saveDownloadedSnapshot(current)
            return
        }

        // Telling the server makes the other devices delete their copies: that is the removal to be approved.
        val allowed = gone.filter { (source, mangaUrl, chapterUrl) ->
            allowRemoval(
                RemovalKind.DOWNLOADED_CHAPTER, RemovalSide.SERVER, "delete-others:${chapterKey(SyncMath.mangaKey(source, mangaUrl), chapterUrl)}",
                "${byKey[SyncMath.mangaKey(source, mangaUrl)]?.safeTitle() ?: mangaUrl} - $chapterUrl: downloaded copy on the server's other devices",
            )
        }
        // The picture of what is downloaded moves only when nothing is waiting, or the held deletions are forgotten.
        if (allowed.size == gone.size) saveDownloadedSnapshot(current)
        if (allowed.isEmpty()) return

        val ids = allowed.mapNotNull { (source, mangaUrl, chapterUrl) ->
            SyncMath.parseSourceId(source)?.let { api.lookupChapter(it, mangaUrl, chapterUrl)?.chapterId }
        }
        if (ids.isEmpty()) return
        api.requestDeletions(ids, deleteFromServer = false)
        summary = summary.copy(deletionsPushed = ids.size)

        // Read our own announcements back so the cursor moves past them without applying them here.
        if (preferences.applyRemoteDeletions().get()) {
            applyDeletions(skip = allowed.mapTo(mutableSetOf()) { (source, mangaUrl, chapterUrl) ->
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

            // A source that leaves the group on either side is a removal; a changed url or order is not.
            val staying = outcome.result.mapTo(mutableSetOf()) { it.sourceId }
            var blocked = false
            if (outcome.changeRemote) {
                for (e in remote.filter { it.sourceId !in staying }) {
                    if (!allowRemoval(RemovalKind.MERGED_SOURCE, RemovalSide.SERVER, "merged-server:$key|${e.sourceId}", "${manga.safeTitle()}: merged source ${e.sourceId} on the server")) blocked = true
                }
            }
            if (outcome.changeLocal) {
                for (e in local.filter { it.sourceId !in staying }) {
                    if (!allowRemoval(RemovalKind.MERGED_SOURCE, RemovalSide.THIS_DEVICE, "merged-here:$key|${e.sourceId}", "${manga.safeTitle()}: merged source ${e.sourceId} on this device")) blocked = true
                }
            }
            if (blocked) {
                snapshot[key]?.let { newSnapshot[key] = it } // what was agreed stays, so the removal is found again
                continue
            }

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

    // region extensions

    /**
     * Installed extensions are the union of both sides; uninstalls are not carried over. The server installs what the app
     * has, and the app installs what the server has. An extension the server installed is trusted here once the app loads
     * it, which is on the sync after the install.
     *
     * Only what is needed is asked of the server: the short list of what it has installed, and then just the extensions
     * this device has installed that are not on that list. One extension that cannot be installed (not in the server's
     * repositories, a failed download) is reported and the others still go.
     */
    private suspend fun syncExtensions() {
        val remoteInstalled = api.installedExtensions()
        val localInstalled = extensionManager.installedExtensionsFlow.value.map { it.pkgName }.toSet()

        val toPush = localInstalled - remoteInstalled
        val offeredByServer = if (toPush.isEmpty()) emptySet() else api.extensionsNamed(toPush).mapTo(mutableSetOf()) { it.pkgName }
        val failed = mutableListOf<String>()
        for (pkgName in toPush.filter { it in offeredByServer }) {
            try {
                api.installExtension(pkgName)
            } catch (e: ServerApiException) {
                Logger.w(e) { "Suwayomi sync: the server could not install $pkgName" }
                failed += pkgName
            }
        }
        if (failed.isNotEmpty()) summary = summary.copy(errors = summary.errors + "extensions: the server could not install ${failed.joinToString()}")

        val missingHere = remoteInstalled - localInstalled
        if (missingHere.isNotEmpty()) {
            fun installable() = extensionManager.availableExtensionsFlow.value
                .filter { it.pkgName in missingHere && extensionManager.getInstallInfo(it.pkgName) == null }
            // The list of what the repositories offer is old when a repository arrived in this very sync: look again once.
            val wanted = missingHere.filter { extensionManager.getInstallInfo(it) == null }
            if (wanted.any { pkg -> extensionManager.availableExtensionsFlow.value.none { it.pkgName == pkg } }) {
                extensionManager.findAvailableExtensions()
            }
            val infos = installable().map { ExtensionManager.ExtensionInfo(it) }
            if (infos.isNotEmpty()) ExtensionInstallerJob.startJob(app, infos)
        }

        extensionManager.untrustedExtensionsFlow.value
            .filter { it.pkgName in remoteInstalled }
            .forEach { extensionManager.trust(it.pkgName, it.versionCode, it.signatureHash) }
    }

    // region extension repositories

    /**
     * The same repository whatever way it is spelled: with or without the index file name, and GitHub's two addresses for a
     * file (`github.com/owner/repo/raw/ref/path` is `raw.githubusercontent.com/owner/repo/ref/path`; the server often keeps
     * the first and the app the second).
     */
    private fun normalizeRepoUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
            .removeSuffix("/index.min.json").removeSuffix("/index.json").removeSuffix("/index.pb").removeSuffix("/repo.json")
            .trimEnd('/')
        val github = GITHUB_RAW.matchEntire(trimmed) ?: return trimmed
        val (owner, repo, ref, path) = github.destructured
        return "https://raw.githubusercontent.com/$owner/$repo/$ref${if (path.isEmpty()) "" else "/$path"}"
    }

    /**
     * The server's own log of a repository, whatever way it spells the address: its newest operation, if the log has
     * one and it is a removal.
     */
    private fun remoteRepoOp(base: String): Pair<String, Long>? =
        remoteLatest.keys.filter { it.startsWith("repo:") && normalizeRepoUrl(it.removePrefix("repo:")) == base }
            .maxByOrNull { remoteLatest.getValue(it) }?.let { remoteAction.getValue(it) to remoteLatest.getValue(it) }

    /** Removed on the server (the web, another device) after anything this device did with it: no snapshot needed to believe it. */
    private fun removedOnServerByLog(base: String): Boolean {
        if (!opsAvailable || cursorAtStart == 0L) return false
        val (action, ts) = remoteRepoOp(base) ?: return false
        if (action != OpAction.REMOVE) return false
        val mine = operations.lastOp(OpEntity.EXTENSION_REPO, base) ?: return true
        return mine.ts + clockOffset <= ts
    }

    /** Removed on this device after anything the server did with it. */
    private fun removedHereByLog(base: String, indexUrl: String): Boolean {
        if (!opsAvailable) return false
        val mine = operations.lastOp(OpEntity.EXTENSION_REPO, base) ?: return false
        if (mine.action != OpAction.REMOVE) return false
        val remote = remoteRepoOp(base) ?: return true
        return remote.first == OpAction.REMOVE || mine.ts + clockOffset > remote.second
    }

    private fun loadRepoSnapshot(): Set<String>? {
        val raw = preferences.extensionRepoSnapshot().get()
        if (raw.isBlank()) return null
        return try {
            json.decodeFromString(ListSerializer(String.serializer()), raw).toSet()
        } catch (e: Exception) {
            Logger.w(e) { "Ignoring unreadable Suwayomi sync snapshot" }
            null // unreadable = first run: a union, which loses nothing
        }
    }

    /**
     * Makes both sides know the same extension repositories. Adding is a union in both directions;
     * a removal is only believed when the previous snapshot proves the repository was on both sides
     * (the first run has no snapshot and removes nothing), and then only follows the options already
     * used for deletions: [ServerPreferences.pushLocalDeletions] to remove it from the server,
     * [ServerPreferences.applyRemoteDeletions] to remove it here. When the other side's removal
     * collides with a newer action on this side, the operation log decides who is right.
     */
    private suspend fun syncExtensionRepos() {
        val localByBase = getExtensionRepo.getAll().associate { normalizeRepoUrl(it.baseUrl) to it.baseUrl }
        val remoteByBase = api.extensionStores().associateBy { normalizeRepoUrl(it) }
        val snapshot = loadRepoSnapshot()

        val local = localByBase.keys.toMutableSet()
        val remote = remoteByBase.keys.toMutableSet()
        val failed = mutableSetOf<String>()
        var pulled = 0
        var pushed = 0
        var removedLocal = 0
        var removedRemote = 0

        suspend fun addLocal(base: String, indexUrl: String) {
            when (createExtensionRepo.await(indexUrl)) {
                CreateExtensionRepo.Result.Success -> { local += base; pulled++ }
                CreateExtensionRepo.Result.RepoAlreadyExists -> local += base
                else -> {
                    // e.g. a repository the app cannot read: left out of the snapshot so the next run tries again
                    Logger.w { "Suwayomi sync: could not add extension repo $indexUrl here" }
                    failed += base
                }
            }
        }

        suspend fun addRemote(base: String) {
            api.addExtensionStore("$base/repo.json")
            remote += base
            pushed++
        }

        // The server's own spelling of a repository it no longer has, as its log keeps it.
        fun remoteKey(base: String) = remoteLatest.keys.filter { it.startsWith("repo:") && normalizeRepoUrl(it.removePrefix("repo:")) == base }
            .maxByOrNull { remoteLatest.getValue(it) } ?: "repo:$base/repo.json"

        for (base in localByBase.keys - remoteByBase.keys) {
            if (removedOnServerByLog(base)) {
                // the log says it: someone removed it there after anything this device did with it
                if (preferences.applyRemoteDeletions().get() && allowRemoval(RemovalKind.EXTENSION_REPO, RemovalSide.THIS_DEVICE, "repo-here:$base", "Extension repository $base: removed on the server, to be removed on this device")) {
                    deleteExtensionRepo.await(localByBase.getValue(base))
                    local -= base
                    removedLocal++
                } else {
                    // kept, as every deletion not applied; the sync does not push it back either
                }
            } else if (snapshot == null || base !in snapshot) {
                addRemote(base)
            } else if (winner(remoteKey(base), operations.latest(OpEntity.EXTENSION_REPO, name = base)) == Side.LOCAL) {
                addRemote(base) // removed on the server, but this device re-added it later
            } else if (preferences.applyRemoteDeletions().get() &&
                allowRemoval(RemovalKind.EXTENSION_REPO, RemovalSide.THIS_DEVICE, "repo-here:$base", "Extension repository $base: removed on the server, to be removed on this device")
            ) {
                deleteExtensionRepo.await(localByBase.getValue(base))
                local -= base
                removedLocal++
            }
        }
        for (base in remoteByBase.keys - localByBase.keys) {
            val indexUrl = remoteByBase.getValue(base)
            if (removedHereByLog(base, indexUrl)) {
                // the log says it: this device removed it after anything the server did with it
                if (preferences.pushLocalDeletions().get() && allowRemoval(RemovalKind.EXTENSION_REPO, RemovalSide.SERVER, "repo-server:$base", "Extension repository $base: removed here, to be removed on the server")) {
                    api.removeExtensionStore(indexUrl)
                    remote -= base
                    removedRemote++
                }
            } else if (snapshot == null || base !in snapshot) {
                addLocal(base, indexUrl)
            } else if (winner("repo:$indexUrl", operations.latest(OpEntity.EXTENSION_REPO, name = base)) == Side.REMOTE) {
                addLocal(base, indexUrl) // removed here, but the server re-added it later
            } else if (preferences.pushLocalDeletions().get() &&
                allowRemoval(RemovalKind.EXTENSION_REPO, RemovalSide.SERVER, "repo-server:$base", "Extension repository $base: removed here, to be removed on the server")
            ) {
                api.removeExtensionStore(indexUrl)
                remote -= base
                removedRemote++
            }
        }

        val next = (local + remote - failed).sorted()
        preferences.extensionRepoSnapshot().set(json.encodeToString(ListSerializer(String.serializer()), next))
        summary = summary.copy(reposPulled = pulled, reposPushed = pushed, reposRemovedLocal = removedLocal, reposRemovedRemote = removedRemote)
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
            .associateByTo(mutableMapOf()) { SyncMath.mangaKey(it.source, it.url) }
        // A pair that left this device's library since the last sync was taken out on purpose: take
        // it out of the server's library too, before the walk below could bring it back.
        val lastLibrary = loadLibrarySnapshot()
        // Works whose removal is waiting for approval: they stay in the snapshot, and the walk below must not bring them back.
        val heldWorks = mutableSetOf<String>()
        if (lastLibrary != null && local.isNotEmpty()) {
            for (key in lastLibrary) {
                if (key in local) continue
                val sourceId = key.substringBefore('|').toLongOrNull() ?: continue
                val onServer = api.lookupManga(sourceId, key.substringAfter('|')) ?: continue
                if (allowRemoval(RemovalKind.LIBRARY_WORK, RemovalSide.SERVER, "library-server:$key", "${key.substringAfter('|')}: out of the library on the server")) {
                    api.removeFromLibrary(onServer.mangaId)
                } else {
                    heldWorks += key
                }
            }
        }
        val linkSnapshot = loadLinkSnapshot()
        val newLinkSnapshot = mutableMapOf<String, Set<String>>()
        val progressSnapshot = loadProgressSnapshot()
        val newProgressSnapshot = mutableMapOf<String, Map<String, Int>>()
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
                if (key in heldWorks) continue
                val manga = local[key] ?: importManga(remote)?.also { local[key] = it } ?: continue
                cacheCover(manga, remote)
                if (categories != null) {
                    val (l, r, names) = reconcileLinks(manga, remote, categories, linkSnapshot[key].orEmpty())
                    linksLocal += l
                    linksRemote += r
                    newLinkSnapshot[key] = names
                }
                if (progress) {
                    val (p, q, agreed) = reconcileProgress(manga, remote, remotePatches, progressSnapshot[key].orEmpty())
                    pulled += p
                    pushed += q
                    if (agreed.isNotEmpty()) newProgressSnapshot[key] = agreed
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

        saveLibrarySnapshot(local.values, heldWorks)
        if (categories != null) saveLinkSnapshot(newLinkSnapshot)
        if (progress) saveProgressSnapshot(newProgressSnapshot)
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
     * Creates the server's library manga this device does not have yet, with its chapters, as a favourite.
     * Returns null when the server's source id is not a number.
     */
    private suspend fun importManga(remote: RemoteManga): Manga? {
        val source = remote.sourceId.toLongOrNull() ?: return null
        val manga = getManga.awaitByUrlAndSource(remote.url, source)?.also {
            if (!it.favorite) updateManga.await(MangaUpdate(id = it.id!!, favorite = true))
        } ?: MangaImpl(source = source, url = remote.url).apply {
            title = remote.title
            thumbnail_url = remote.thumbnailUrl
            artist = remote.artist
            author = remote.author
            description = remote.description
            genre = remote.genres.joinToString()
            status = serverStatus[remote.status] ?: SManga.UNKNOWN
            favorite = true
            initialized = true
            date_added = System.currentTimeMillis()
            id = insertManga.await(this)
        }
        val mangaId = manga.id ?: return null
        val have = getChapter.awaitAllRaw(mangaId, false).mapTo(mutableSetOf()) { it.url }
        val missing = remote.chapters.filter { it.url !in have }.map { c ->
            Chapter.create().apply {
                url = c.url
                name = c.name
                chapter_number = c.chapterNumber
                scanlator = c.scanlator
                date_upload = c.uploadDate
                source_order = c.sourceOrder
                manga_id = mangaId
            }
        }
        if (missing.isNotEmpty()) insertChapter.awaitBulk(missing)
        return manga
    }

    /**
     * Keeps the server's cover on the device, so the app shows it without the server. Only covers the
     * server supplied are fetched; a manga whose cover came from a source is left as it is.
     */
    private suspend fun cacheCover(manga: Manga, remote: RemoteManga) {
        val path = remote.thumbnailUrl ?: return
        if (manga.thumbnail_url != path) return
        // Kept as the manga's custom cover: the library reads that file first, with no URL or credentials.
        if (coverCache.getCustomCoverFile(manga).exists()) return
        try {
            coverCache.setCustomCoverToCache(manga, api.downloadCover(path).inputStream())
        } catch (e: Exception) {
            Logger.w(e) { "Suwayomi sync: cover of ${remote.url} not fetched" }
        }
    }

    /** The server's status names, as the app's SManga constants. */
    private val serverStatus = mapOf(
        "ONGOING" to SManga.ONGOING,
        "COMPLETED" to SManga.COMPLETED,
        "LICENSED" to SManga.LICENSED,
        "PUBLISHING_FINISHED" to SManga.PUBLISHING_FINISHED,
        "CANCELLED" to SManga.CANCELLED,
        "ON_HIATUS" to SManga.ON_HIATUS,
    )

    /** How a local category corresponds to a server one, and the name both agreed on last time. */
    @Serializable
    internal data class MappedCategory(val remoteId: Int, val name: String)

    private val categoryMapSerializer = MapSerializer(String.serializer(), MappedCategory.serializer())

    private fun loadCategoryMap(): Map<Long, MappedCategory> =
        decode(preferences.categoryMapSnapshot().get()) { json.decodeFromString(categoryMapSerializer, it) }
            .mapKeys { it.key.toLong() }

    private fun saveCategoryMap(map: Map<Long, MappedCategory>) {
        preferences.categoryMapSnapshot().set(json.encodeToString(categoryMapSerializer, map.mapKeys { it.key.toString() }))
    }

    /**
     * Makes both sides know the same categories, creating what is missing wherever it is, and carrying
     * a rename across.
     *
     * A category is the same one on both sides because it was matched once and remembered
     * ([MappedCategory]); that memory is what lets a changed name be a rename and not a new category.
     * Renamed on one side: the other follows. Renamed on both, differently: the later one wins (by the
     * operation log, the server's name when the log cannot say). Whatever is not remembered yet is
     * matched by name, as before. Deleting a category never propagates — it would take the manga links
     * with it — so one deleted on a side is simply made again from the other.
     */
    private suspend fun reconcileCategoryNames(): CategoryMap {
        val localCategories = getCategories.await().filter { it.isSyncable() }
        val remoteCategories = api.categories().filter { !it.isDefault && it.id != 0 }.toMutableList()

        val remoteById = remoteCategories.associateBy { it.id }.toMutableMap()
        val localById = localCategories.associateBy { it.id!!.toLong() }
        val pairs = mutableMapOf<Long, MappedCategory>()
        var renamedLocal = 0
        var renamedRemote = 0

        // 1. what was matched before: has either side renamed it?
        for ((localId, mapped) in loadCategoryMap()) {
            val local = localById[localId] ?: continue
            val remote = remoteById[mapped.remoteId] ?: continue
            val agreed = mapped.name
            val localName = local.name.trim()
            val remoteName = remote.name.trim()
            val localChanged = localName != agreed
            val remoteChanged = remoteName != agreed
            val name = when {
                !localChanged && !remoteChanged -> agreed
                localName == remoteName -> localName
                localChanged && !remoteChanged -> localName.also { renameRemote(remote.id, it); renamedRemote++ }
                remoteChanged && !localChanged -> remoteName.also { renameLocal(local, it); renamedLocal++ }
                else -> {
                    // both renamed, differently: the one done later is the real name
                    val side = winner("category:${remote.id}", operations.latest(OpEntity.CATEGORY, categoryId = localId))
                    if (side == Side.LOCAL) {
                        localName.also { renameRemote(remote.id, it); renamedRemote++ }
                    } else {
                        remoteName.also { renameLocal(local, it); renamedLocal++ }
                    }
                }
            }
            pairs[localId] = MappedCategory(remote.id, name)
        }

        // 2. what was never matched: by name, then create what the other side lacks
        val takenRemote = pairs.values.mapTo(mutableSetOf()) { it.remoteId }
        val freeLocal = localCategories.filter { it.id!!.toLong() !in pairs }.associateBy { SyncMath.categoryKey(it.name) }.toMutableMap()
        val freeRemote = remoteCategories.filter { it.id !in takenRemote }.associateBy { SyncMath.categoryKey(it.name) }.toMutableMap()

        for (key in freeLocal.keys.intersect(freeRemote.keys)) {
            val local = freeLocal.getValue(key)
            val remote = freeRemote.getValue(key)
            pairs[local.id!!.toLong()] = MappedCategory(remote.id, local.name.trim())
        }
        val matched = freeLocal.keys.intersect(freeRemote.keys)
        freeLocal.keys.removeAll(matched)
        freeRemote.keys.removeAll(matched)

        var createdLocal = 0
        var createdRemote = 0
        val maxOrder = localCategories.maxOfOrNull { it.order } ?: 0
        freeRemote.values.forEachIndexed { index, remote ->
            val category = Category.create(remote.name.trim()).apply {
                order = maxOrder + 1 + index
                mangaSort = LibrarySort.Title.categoryValue
            }
            val id = insertCategories.awaitOne(category) ?: return@forEachIndexed
            pairs[id] = MappedCategory(remote.id, remote.name.trim())
            createdLocal++
        }
        for (local in freeLocal.values) {
            val remote = api.createCategory(local.name.trim())
            pairs[local.id!!.toLong()] = MappedCategory(remote.id, local.name.trim())
            createdRemote++
        }

        saveCategoryMap(pairs)
        summary = summary.copy(
            categoriesCreatedLocal = createdLocal,
            categoriesCreatedRemote = createdRemote,
            categoriesRenamedLocal = renamedLocal,
            categoriesRenamedRemote = renamedRemote,
        )
        return CategoryMap(
            localIdByKey = pairs.entries.associate { (localId, m) -> SyncMath.categoryKey(m.name) to localId },
            remoteIdByKey = pairs.values.associate { SyncMath.categoryKey(it.name) to it.remoteId },
        )
    }

    private suspend fun renameRemote(remoteId: Int, name: String) {
        api.renameCategory(remoteId, name)
    }

    private suspend fun renameLocal(category: Category, name: String) {
        updateCategories.awaitOne(CategoryUpdate(id = category.id!!.toLong(), name = name))
        category.name = name
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

        // Taking a work out of a category, on either side, is a removal; adding one is not.
        var blocked = false
        for (category in localKeys - merged) {
            if (!allowRemoval(RemovalKind.CATEGORY_LINK, RemovalSide.THIS_DEVICE, "link-here:${manga.source}|${manga.url}|$category", "${manga.safeTitle()}: out of category $category on this device")) blocked = true
        }
        for (category in remoteKeys - merged) {
            if (!allowRemoval(RemovalKind.CATEGORY_LINK, RemovalSide.SERVER, "link-server:${manga.source}|${manga.url}|$category", "${manga.safeTitle()}: out of category $category on the server")) blocked = true
        }
        if (blocked) return Triple(0, 0, base) // nothing changes for this work, and what was agreed stays

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
     * Merges read state chapter by chapter, matched by chapter url inside the manga, against the
     * state both sides agreed on last time (see [SyncMath.mergeProgress]).
     * Local changes are applied immediately; server changes are queued in [patches] so the whole
     * page goes out as a few requests.
     * @return chapters changed locally, chapters queued for the server, and the agreed states to remember.
     */
    private suspend fun reconcileProgress(
        manga: Manga,
        remote: RemoteManga,
        patches: MutableList<ServerSyncApi.ChapterPatch>,
        base: Map<String, Int>,
    ): Triple<Int, Int, Map<String, Int>> {
        val mangaId = manga.id!!
        val remoteByUrl = remote.chapters.associateBy { it.url }
        if (remoteByUrl.isEmpty()) return Triple(0, 0, emptyMap())

        val updates = mutableListOf<ChapterUpdate>()
        val agreed = mutableMapOf<String, Int>()
        var queued = 0
        for (chapter in getChapter.awaitAllRaw(mangaId, false)) {
            val remoteChapter = remoteByUrl[chapter.url] ?: continue
            val chapterId = chapter.id ?: continue
            val localState = Progress(chapter.read, chapter.last_page_read)
            val remoteState = remoteChapter.toProgress()
            val baseState = base[chapter.url]?.let { SyncMath.unpackProgress(it) } ?: Progress(false, 0)
            // Both moved it: the log says whose change was made last. Without an answer, the older
            // rule decides (read on either side is read, the farther page wins).
            val conflict = localState != baseState && remoteState != baseState && localState != remoteState
            val side = if (conflict) winner(progressKey(manga, chapter.url), operations.latest(OpEntity.PROGRESS, chapterId = chapterId)) else null
            val merged = when (side) {
                Side.LOCAL -> localState
                Side.REMOTE -> remoteState
                null -> SyncMath.mergeProgress(baseState, localState, remoteState)
            }

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
            if (merged.read || merged.lastPageRead > 0) agreed[chapter.url] = with(SyncMath) { merged.pack() }
        }
        if (updates.isNotEmpty()) updateChapter.awaitAll(updates)
        return Triple(updates.size, queued, agreed)
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
            val baseValue = base[chapter.url] ?: 0L
            val remoteValue = remoteChapter.lastReadAt
            val conflict = local != baseValue && remoteValue != baseValue && local != remoteValue
            val side = if (conflict) winner(historyKey(manga, chapter.url), operations.latest(OpEntity.HISTORY, chapterId = chapterId)) else null
            val outcome = when (side) {
                Side.LOCAL -> SyncMath.HistoryOutcome(local, changeLocal = false, changeRemote = true)
                Side.REMOTE -> SyncMath.HistoryOutcome(remoteValue, changeLocal = true, changeRemote = false)
                null -> SyncMath.mergeHistory(baseValue, local, remoteValue)
            }

            // A chapter leaving the history, here or on the server, is a removal; a newer reading time is not.
            val entry = "${manga.source}|${manga.url}|${chapter.url}"
            var blockedHistory = false
            if (outcome.changeLocal && outcome.result == 0L && local > 0) {
                if (!allowRemoval(RemovalKind.HISTORY_ENTRY, RemovalSide.THIS_DEVICE, "history-here:$entry", "${manga.safeTitle()} - ${chapter.safeName()}: out of the history on this device")) blockedHistory = true
            }
            if (outcome.changeRemote && outcome.result == 0L && remoteValue > 0) {
                if (!allowRemoval(RemovalKind.HISTORY_ENTRY, RemovalSide.SERVER, "history-server:$entry", "${manga.safeTitle()} - ${chapter.safeName()}: out of the history on the server")) blockedHistory = true
            }
            if (blockedHistory) {
                if (baseValue > 0) agreed[chapter.url] = baseValue // what was agreed stays, so the removal is found again
                continue
            }

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

    private val progressSnapshotSerializer =
        MapSerializer(String.serializer(), MapSerializer(String.serializer(), Int.serializer()))

    private fun loadProgressSnapshot(): Map<String, Map<String, Int>> =
        decode(preferences.progressSnapshot().get()) { json.decodeFromString(progressSnapshotSerializer, it) }

    private fun saveProgressSnapshot(snapshot: Map<String, Map<String, Int>>) {
        preferences.progressSnapshot().set(json.encodeToString(progressSnapshotSerializer, snapshot))
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

    private val librarySnapshotSerializer = ListSerializer(String.serializer())

    /** The `sourceId|url` keys of the library at the last sync; null when there is none usable (then nothing is taken out). */
    private fun loadLibrarySnapshot(): List<String>? {
        val raw = preferences.librarySnapshot().get()
        if (raw.isBlank()) return null
        return try {
            json.decodeFromString(librarySnapshotSerializer, raw)
        } catch (e: Exception) {
            Logger.w(e) { "Ignoring unreadable Suwayomi library snapshot" }
            null
        }
    }

    private fun saveLibrarySnapshot(library: Collection<Manga>, alsoKeep: Set<String> = emptySet()) {
        preferences.librarySnapshot().set(
            json.encodeToString(librarySnapshotSerializer, library.map { SyncMath.mangaKey(it.source, it.url) } + alsoKeep),
        )
    }

    private val linkSnapshotSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    /** The policy the server last reported, for callers that only need to read it. */
    fun cachedPolicy(): DownloadPolicy = DownloadPolicy.parse(preferences.serverDownloadPolicy().get())

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** Karasu keeps history times in milliseconds, the server in seconds. */
        const val MILLIS = 1000L

        const val OPERATIONS_PAGE = 1000

        /** `https://github.com/{owner}/{repo}/raw/{ref}/{path}`, GitHub's second address for a raw file. */
        val GITHUB_RAW = Regex("""^https://github\.com/([^/]+)/([^/]+)/raw/([^/]+)(?:/(.*))?$""")
    }
}
