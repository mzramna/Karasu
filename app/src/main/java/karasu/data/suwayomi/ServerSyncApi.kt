package karasu.data.suwayomi

import eu.kanade.tachiyomi.network.NetworkHelper
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import karasu.domain.suwayomi.ServerPreferences
import karasu.domain.suwayomi.models.ApiError
import karasu.domain.suwayomi.models.BackupInfo
import karasu.domain.suwayomi.models.ChapterLookup
import karasu.domain.suwayomi.models.DeletionEntry
import karasu.domain.suwayomi.models.DeletionQueued
import karasu.domain.suwayomi.models.DeletionRequest
import karasu.domain.suwayomi.models.DownloadPolicy
import karasu.domain.suwayomi.models.HistoryBody
import karasu.domain.suwayomi.models.HistoryEntry
import karasu.domain.suwayomi.models.HistoryUpdated
import karasu.domain.suwayomi.models.MangaLookup
import karasu.domain.suwayomi.models.MangaRef
import karasu.domain.suwayomi.models.MergedGroup
import karasu.domain.suwayomi.models.MergedSource
import karasu.domain.suwayomi.models.MergedSourcesBody
import karasu.domain.suwayomi.models.PolicyBody
import karasu.domain.suwayomi.models.PutMergedBody
import karasu.domain.suwayomi.models.ServerHealth
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source

/** The server answered, but not with what was asked for. [code] is the HTTP status. */
class ServerApiException(val code: Int, message: String) : IOException(message)

/** Result of [ServerSyncApi.health]: the server's own report plus what the app measured. */
data class HealthResult(
    val health: ServerHealth,
    val latencyMs: Long,
    val origin: ServerOrigin,
    val baseUrl: String,
)

/**
 * Client for the Karasu REST contract and the slice of Suwayomi's GraphQL the sync uses.
 *
 * Every call goes through [ServerTransport], so each one gets the LAN-first/fallback behaviour
 * and the Basic Auth header without knowing about either. Two kinds of failure are told apart:
 * an [IOException] that is *not* a [ServerApiException] means nothing answered (the caller should
 * treat the server as unreachable), while a [ServerApiException] means the server answered "no".
 */
class ServerSyncApi(
    private val transport: ServerTransport,
    val reachability: ServerReachability,
    /** The master switch, read on every use so flipping it takes effect without restarting anything. */
    private val isSwitchedOn: () -> Boolean = { true },
) {

    private val json = Json {
        ignoreUnknownKeys = true
        // Needed for `updatesEnabled = true`, which is a default and would otherwise be left out
        // of the body and read by the server as absent.
        encodeDefaults = true
    }

    /** Switched on and an address entered: the condition under which background work is allowed to run. */
    val isEnabled: Boolean get() = isSwitchedOn() && isConfigured

    /** An address is entered. "Test connection" only needs this, not the master switch. */
    val isConfigured: Boolean get() = transport.isConfigured

    // region REST: health

    suspend fun health(): HealthResult {
        val started = System.nanoTime()
        try {
            val answer = transport.execute { get(it, "health") }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            answer.response.use { response ->
                val health = response.decode<ServerHealth>()
                return HealthResult(health, elapsed, answer.origin, answer.base.toString())
            }
        } catch (e: ServerApiException) {
            // Suwayomi answers 404 (not 401) to a request with bad credentials, and an upstream
            // server without the Karasu routes answers 404 too: say both, or the user hunts the wrong one.
            if (e.code in AUTH_OR_MISSING) {
                throw ServerApiException(e.code, "${e.message} - check the username and password, and that the server is the Karasu-compatible Suwayomi-Server")
            }
            throw e
        }
    }

    // endregion

    // region REST: chapter archives and lookups

    /**
     * Streams a downloaded chapter's CBZ into [block], which must consume it before returning.
     *
     * Returns null when the server does not have the chapter downloaded (404) — that is an
     * ordinary answer, not an error, because the contract never makes the server fetch it.
     * [block] receives the stream and the announced length (or -1).
     */
    suspend fun <T> openChapterArchive(chapterId: Long, block: suspend (InputStream, Long) -> T): T? {
        val answer = transport.execute { get(it, "chapter/$chapterId/archive") }
        return answer.response.use { response ->
            if (response.code == 404) return@use null
            response.requireSuccess()
            val body = response.body
            withContext(Dispatchers.IO) { block(body.byteStream(), body.contentLength()) }
        }
    }

    /** Size of a chapter's archive without transferring it, or null when it is not downloaded. */
    suspend fun chapterArchiveSize(chapterId: Long): Long? {
        val answer = transport.execute { base ->
            Request.Builder().url(base.endpoint("chapter/$chapterId/archive")).head().build()
        }
        return answer.response.use { response ->
            if (response.code == 404) return@use null
            response.requireSuccess()
            response.header("Content-Length")?.toLongOrNull() ?: -1L
        }
    }

    suspend fun lookupChapter(sourceId: Long, mangaUrl: String, chapterUrl: String): ChapterLookup? =
        getOrNull(
            "chapter/lookup",
            "sourceId" to sourceId.toString(),
            "mangaUrl" to mangaUrl,
            "chapterUrl" to chapterUrl,
        )

    suspend fun lookupManga(sourceId: Long, url: String): MangaLookup? =
        getOrNull("manga/lookup", "sourceId" to sourceId.toString(), "url" to url)

    // endregion

    // region REST: backups

    /**
     * Uploads a backup. [open] is called for every attempt (once per address tried), so it must
     * return a fresh stream each time; [length] is the size in bytes, or -1 when unknown.
     */
    suspend fun uploadBackup(
        fileName: String,
        device: String,
        length: Long,
        open: () -> InputStream,
    ): BackupInfo {
        val answer = transport.execute { base ->
            val file = object : RequestBody() {
                override fun contentType() = OCTET_STREAM
                override fun contentLength() = length
                override fun writeTo(sink: BufferedSink) {
                    open().use { sink.writeAll(it.source()) }
                }
            }
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, file)
                .build()
            Request.Builder().url(base.endpoint("backups/upload", "device" to device)).post(body).build()
        }
        return answer.response.use { it.decode<BackupInfo>() }
    }

    suspend fun listBackups(): List<BackupInfo> = getJson("backups")

    /** Downloads a backup to [target], replacing it. A partial file is removed on failure. */
    suspend fun downloadBackup(id: String, target: File) {
        val answer = transport.execute { base ->
            Request.Builder().url(base.endpoint(listOf("backups", id, "download"))).get().build()
        }
        answer.response.use { response ->
            response.requireSuccess()
            withContext(Dispatchers.IO) {
                try {
                    target.outputStream().use { out -> response.body.byteStream().copyTo(out) }
                } catch (e: IOException) {
                    target.delete()
                    throw e
                }
            }
        }
    }

    suspend fun deleteBackup(id: String) {
        val answer = transport.execute { base ->
            Request.Builder().url(base.endpoint(listOf("backups", id))).delete().build()
        }
        answer.response.use { it.requireSuccess() }
    }

    // endregion

    // region REST: merged sources

    suspend fun mergedGroups(): List<MergedGroup> = getJson("merged")

    suspend fun mergedGroup(mangaId: Long): MergedGroup = getJson("manga/$mangaId/merged")

    suspend fun putMerged(mangaId: Long, sources: List<MergedSource>): MergedGroup =
        putJson("manga/$mangaId/merged", MergedSourcesBody(sources))

    /**
     * Replaces the merged sources of the work identified by [primary].
     *
     * This is the call Karasu uses, because it only knows a work by its source and url, never by
     * the server's own id. Null when the server has no such work (404): there is nothing to attach
     * the merge to, which the sync treats as "skip", not as a failure.
     */
    suspend fun putMerged(primary: MangaRef, sources: List<MergedSource>): MergedGroup? {
        val body = json.encodeToString(PutMergedBody.serializer(), PutMergedBody(primary, sources))
        val answer = transport.execute { base ->
            Request.Builder().url(base.endpoint("merged")).put(body.toRequestBody(JSON_MEDIA)).build()
        }
        return answer.response.use { response ->
            if (response.code == 404) return@use null
            response.decode<MergedGroup>()
        }
    }

    // endregion

    // region REST: policy and deletions

    suspend fun downloadPolicy(): DownloadPolicy =
        DownloadPolicy.parse(getJson<PolicyBody>("policy").downloadPolicy)

    suspend fun setDownloadPolicy(policy: DownloadPolicy): DownloadPolicy =
        DownloadPolicy.parse(putJson<PolicyBody, PolicyBody>("policy", PolicyBody(policy.name)).downloadPolicy)

    /** Asks the server to record deletions (and optionally delete its own copies). */
    suspend fun requestDeletions(chapterIds: List<Long>, deleteFromServer: Boolean): Int {
        val body = json.encodeToString(DeletionRequest.serializer(), DeletionRequest(chapterIds, deleteFromServer))
        val answer = transport.execute { base ->
            Request.Builder().url(base.endpoint("deletions")).post(body.toRequestBody(JSON_MEDIA)).build()
        }
        return answer.response.use { it.decode<DeletionQueued>().queued }
    }

    /**
     * Sets when chapters were last read, which is what puts them in the server's history; 0 removes
     * a chapter from it. The only way to write that value, since the GraphQL API only ever sets it
     * to "now" as a side effect of changing the page read.
     */
    suspend fun putHistory(entries: List<HistoryEntry>): Int {
        if (entries.isEmpty()) return 0
        val body = json.encodeToString(HistoryBody.serializer(), HistoryBody(entries))
        val answer = transport.execute { base ->
            Request.Builder().url(base.endpoint("history")).put(body.toRequestBody(JSON_MEDIA)).build()
        }
        return answer.response.use { it.decode<HistoryUpdated>().updated }
    }

    /** Tombstones with an id greater than [afterId], oldest first. */
    suspend fun deletions(afterId: Long): List<DeletionEntry> =
        getJson("deletions", "afterId" to afterId.toString())

    // endregion

    // region GraphQL

    /** Runs one operation and returns its `data`, failing on a GraphQL `errors` array. */
    suspend fun graphql(query: String, variables: JsonObject = JsonObject(emptyMap())): JsonObject {
        val payload = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }
        val body = json.encodeToString(JsonElement.serializer(), payload)
        val answer = transport.execute { base ->
            Request.Builder().url(base.graphqlEndpoint()).post(body.toRequestBody(JSON_MEDIA)).build()
        }
        val root = answer.response.use { response ->
            response.requireSuccess()
            json.parseToJsonElement(response.body.string()).jsonObject
        }
        val errors = root["errors"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonArray
        if (errors != null && errors.isNotEmpty()) {
            val message = errors.joinToString("; ") {
                (it.jsonObject["message"] as? JsonPrimitive)?.content ?: it.toString()
            }
            throw ServerApiException(200, "GraphQL: $message")
        }
        return root["data"]?.jsonObject ?: throw ServerApiException(200, "GraphQL: empty response")
    }

    suspend fun categories(): List<RemoteCategory> =
        json.decodeFromJsonElement<CategoriesData>(graphql(ServerGraphQl.CATEGORIES)).categories.nodes

    suspend fun createCategory(name: String): RemoteCategory {
        val data = graphql(ServerGraphQl.CREATE_CATEGORY, buildJsonObject { put("name", name) })
        return json.decodeFromJsonElement<CreateCategoryData>(data).createCategory.category
    }

    suspend fun updateMangaCategories(mangaId: Int, toAdd: Collection<Int>, toRemove: Collection<Int>) {
        if (toAdd.isEmpty() && toRemove.isEmpty()) return
        graphql(
            ServerGraphQl.UPDATE_MANGA_CATEGORIES,
            buildJsonObject {
                put("id", mangaId)
                if (toAdd.isNotEmpty()) putJsonArray("add") { toAdd.forEach { id -> add(JsonPrimitive(id)) } }
                if (toRemove.isNotEmpty()) putJsonArray("remove") { toRemove.forEach { id -> add(JsonPrimitive(id)) } }
            },
        )
    }

    /**
     * Walks the server's library page by page, handing each page to [onPage].
     *
     * A page at a time keeps memory flat for a big library, and lets the caller reconcile as it
     * goes instead of holding every chapter of every manga at once.
     */
    suspend fun forEachLibraryPage(pageSize: Int = LIBRARY_PAGE_SIZE, onPage: suspend (List<RemoteManga>) -> Unit) {
        var offset = 0
        while (true) {
            val data = graphql(
                ServerGraphQl.LIBRARY,
                buildJsonObject {
                    put("first", pageSize)
                    put("offset", offset)
                },
            )
            val page = json.decodeFromJsonElement<LibraryData>(data).mangas
            if (page.nodes.isNotEmpty()) onPage(page.nodes.map { it.toRemote() })
            if (!page.pageInfo.hasNextPage || page.nodes.isEmpty()) return
            offset += page.nodes.size
        }
    }

    /** One chapter's new state on the server. [lastPageRead] is the 0-based page the reader stopped on. */
    data class ChapterPatch(val chapterId: Int, val isRead: Boolean, val lastPageRead: Int)

    suspend fun updateChapters(patches: List<ChapterPatch>) {
        patches.chunked(CHAPTER_UPDATE_BATCH).forEach { batch ->
            val variables = buildJsonObject {
                batch.forEachIndexed { index, patch ->
                    put("id$index", patch.chapterId)
                    putJsonObject("patch$index") {
                        put("isRead", patch.isRead)
                        put("lastPageRead", patch.lastPageRead)
                    }
                }
            }
            graphql(ServerGraphQl.updateChaptersDocument(batch.size), variables)
        }
    }

    // endregion

    // region plumbing

    private fun get(base: HttpUrl, path: String, vararg query: Pair<String, String>): Request =
        Request.Builder().url(base.endpoint(path, *query)).get().build()

    private suspend inline fun <reified T> getJson(path: String, vararg query: Pair<String, String>): T {
        val answer = transport.execute { get(it, path, *query) }
        return answer.response.use { it.decode<T>() }
    }

    /** 404 is "not there", which for lookups is a normal answer. */
    private suspend inline fun <reified T> getOrNull(path: String, vararg query: Pair<String, String>): T? {
        val answer = transport.execute { get(it, path, *query) }
        return answer.response.use { if (it.code == 404) null else it.decode<T>() }
    }

    private suspend inline fun <reified B, reified T> putJson(path: String, body: B): T {
        val text = json.encodeToString(kotlinx.serialization.serializer<B>(), body)
        val answer = transport.execute { base ->
            Request.Builder().url(base.endpoint(path)).put(text.toRequestBody(JSON_MEDIA)).build()
        }
        return answer.response.use { it.decode<T>() }
    }

    private inline fun <reified T> Response.decode(): T {
        requireSuccess()
        return json.decodeFromString(body.string())
    }

    /** Turns a non-2xx answer into [ServerApiException], using the contract's `{"error": ...}` when present. */
    private fun Response.requireSuccess() {
        if (isSuccessful) return
        val text = runCatching { peekBody(ERROR_BODY_BYTES).string() }.getOrDefault("")
        val detail = runCatching { json.decodeFromString<ApiError>(text).error }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: text.take(ERROR_SNIPPET).ifBlank { message }
        throw ServerApiException(code, "HTTP $code: $detail".trimEnd(' ', ':'))
    }

    companion object {
        private val AUTH_OR_MISSING = setOf(401, 403, 404)

        /** Wires the transport to the user's settings and the app's shared HTTP client. */
        fun create(
            preferences: ServerPreferences,
            network: NetworkHelper,
            reachability: ServerReachability,
        ) = ServerSyncApi(
            transport = ServerTransport(
                config = {
                    ServerConfig(
                        primaryUrl = preferences.primaryUrl().get(),
                        secondaryUrl = preferences.secondaryUrl().get(),
                        username = preferences.username().get(),
                        password = preferences.password().get(),
                    )
                },
                baseClient = network.client,
                reachability = reachability,
                onGoodOrigin = { preferences.lastGoodOrigin().set(it.name.lowercase()) },
            ),
            reachability = reachability,
            isSwitchedOn = { preferences.enabled().get() },
        )

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private const val ERROR_BODY_BYTES = 1024L
        private const val ERROR_SNIPPET = 200
        private const val LIBRARY_PAGE_SIZE = 20
        private const val CHAPTER_UPDATE_BATCH = 50
    }

    // endregion
}

internal const val API_PREFIX = "api/v1/karasu"

/** `{base}/api/v1/karasu/{path}?query`, keeping any subpath the server is mounted under. */
internal fun HttpUrl.endpoint(path: String, vararg query: Pair<String, String>): HttpUrl =
    endpoint(path.split('/'), *query)

/**
 * Same, with each element added as exactly one path segment, so a value that contains a slash or
 * a percent sign (a backup file name) is encoded rather than reinterpreted.
 */
internal fun HttpUrl.endpoint(segments: List<String>, vararg query: Pair<String, String>): HttpUrl =
    newBuilder().apply {
        API_PREFIX.split('/').forEach { addPathSegment(it) }
        segments.forEach { addPathSegment(it) }
        query.forEach { (key, value) -> addQueryParameter(key, value) }
    }.build()

internal fun HttpUrl.graphqlEndpoint(): HttpUrl = newBuilder().addPathSegments("api/graphql").build()
