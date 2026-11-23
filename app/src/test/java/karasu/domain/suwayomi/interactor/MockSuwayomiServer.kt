package karasu.domain.suwayomi.interactor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A Suwayomi-Server (the mzramna fork) that lives in memory: the REST routes under `/api/v1/karasu/` and the
 * GraphQL documents the app sends, behaving the way the real server does, including the parts that are easy to
 * get wrong — a page stored rounded down to the pages the server knows, the "now" stamp a page change leaves
 * on `lastReadAt`, a wrong password answered with 404, and an operation log that records what the web does but
 * not what a device that announced itself did.
 *
 * It exists so the sync scenarios can run on every build in seconds. It earns trust because the very same
 * scenarios run against the real server in the `CI Integration` workflow: when they disagree, the mock is
 * what gets fixed.
 */
class MockSuwayomiServer(
    works: Int = 40,
    /** The server's clock; a test moves it to say "the server thinks it is later than you do". */
    @Volatile var clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val USER = "karasu"
        const val PASS = "s3cret"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** A real, tiny PNG; the archive only has to be a valid zip with images in it. */
        private val PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )
    }

    private class Chapter(
        val id: Int,
        val url: String,
        var downloaded: Boolean,
        val pageCount: Int,
        var read: Boolean = false,
        var lastPageRead: Int = 0,
        /** Epoch seconds; 0 = not in the history. */
        var lastReadAt: Long = 0,
    )

    private class Manga(val id: Int, val sourceId: String, val url: String, val chapters: List<Chapter>) {
        val categoryIds = linkedSetOf<Int>()
    }

    private class Category(val id: Int, var name: String, val order: Int, val isDefault: Boolean = false)

    private class Backup(val id: String, val name: String, val device: String?, val createdAt: Long, val bytes: ByteArray)

    private class Tombstone(val id: Int, val sourceId: String, val mangaUrl: String, val chapterUrl: String, val chapterNumber: Float, val requestedAt: Long)

    private class Op(val seq: Long, val json: JsonObject)

    private val lock = Any()
    private val mangas = mutableListOf<Manga>()
    private val categories = mutableListOf(Category(0, "Default", 0, isDefault = true))
    private val merged = mutableMapOf<Int, List<JsonObject>>()
    private val backups = mutableListOf<Backup>()
    private val tombstones = mutableListOf<Tombstone>()
    private val operations = mutableListOf<Op>()
    private val operationIds = mutableSetOf<String>()
    /** False imitates a server from before the operation log: its routes do not exist. */
    @Volatile var operationLogEnabled = true

    private var policy = "SERVER_ONLY"
    private var nextCategoryId = 1
    private var nextSeq = 1L
    private var nextTombstone = 1

    // On plain sockets: the unit-test classpath is Android's and has no com.sun.net.httpserver.
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    @Volatile
    private var running = true
    val url: String get() = "http://127.0.0.1:${socket.localPort}"

    /** Every request the server received: method, path+query, and the device header ("" when absent). */
    val requests = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, String>>()

    init {
        var chapterId = 1
        for (n in 1..works) {
            val url = "/live/m%02d".format(n)
            val chapters = (1..4).map { k ->
                Chapter(chapterId++, "$url/c$k", downloaded = k <= 2, pageCount = if (k <= 2) 3 else -1)
            }
            mangas += Manga(n, "0", url, chapters)
        }
    }

    fun start() {
        thread(isDaemon = true, name = "mock-suwayomi") {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    fun stop() {
        running = false
        socket.close()
    }

    /** The operation log as the server holds it. */
    fun operationLog(): List<JsonObject> = synchronized(lock) { operations.map { it.json } }

    // =========================================================================== plumbing

    private class Request(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray) {
        val path: String get() = target.substringBefore('?')
        val rawQuery: String get() = target.substringAfter('?', "")
    }

    private fun serve(client: Socket) {
        client.use {
            try {
                val input = BufferedInputStream(client.getInputStream())
                val requestLine = readLine(input) ?: return
                val (method, target) = requestLine.split(' ').let { it[0] to it[1] }
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input)
                    if (line.isNullOrEmpty()) break
                    headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(length).also { buffer ->
                    var read = 0
                    while (read < length) {
                        val n = input.read(buffer, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                }
                val reply = handle(Request(method, target, headers, body))
                val head = buildString {
                    append("HTTP/1.1 ${reply.status} X\r\n")
                    append("Content-Type: ${reply.type}\r\n")
                    // a HEAD announces the length it would have sent
                    append("Content-Length: ${reply.headers["Content-Length"] ?: reply.body.size.toString()}\r\n")
                    reply.headers.filterKeys { it != "Content-Length" }.forEach { (k, v) -> append("$k: $v\r\n") }
                    append("Connection: close\r\n\r\n")
                }
                client.getOutputStream().apply {
                    write(head.toByteArray())
                    if (method != "HEAD" && reply.status != 204) write(reply.body)
                    flush()
                }
            } catch (e: Exception) {
                // a client that hung up mid-request is not the test's concern
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1)
            if (b == '\n'.code) return line.toString(Charsets.ISO_8859_1).trimEnd('\r')
            line.write(b)
        }
    }

    private fun handle(req: Request): Reply {
        val path = req.path
        val device = req.headers["x-karasu-device"].orEmpty()
        requests += Triple(req.method, req.target, device)
        if (!authorized(req)) return Reply(404, "text/plain", "Not Found".toByteArray())
        return try {
            synchronized(lock) {
                when {
                    path == "/api/graphql" -> graphql(device, String(req.body))
                    path.startsWith("/api/v1/karasu/") -> rest(req, path.removePrefix("/api/v1/karasu/"), device)
                    else -> reply(404, error("no such route"))
                }
            }
        } catch (e: Exception) {
            Reply(500, "application/json", """{"error":"${e.message?.replace("\"", "'")}"}""".toByteArray())
        }
    }

    private fun authorized(req: Request): Boolean {
        val header = req.headers["authorization"] ?: return false
        if (!header.startsWith("Basic ")) return false
        return String(Base64.getDecoder().decode(header.removePrefix("Basic "))) == "$USER:$PASS"
    }

    private class Reply(val status: Int, val type: String, val body: ByteArray, val headers: Map<String, String> = emptyMap())

    private fun reply(status: Int, json: JsonElement) = Reply(status, "application/json", json.toString().toByteArray())

    private fun error(message: String) = buildJsonObject { put("error", message) }

    // =========================================================================== operation log

    /**
     * What the real server does: a change made by something that did not announce itself (the web) is
     * recorded here, with the server's clock. A device that sends `X-Karasu-Device` publishes its own entries.
     */
    private fun record(device: String, entity: String, key: String, action: String, value: JsonElement?) {
        if (device.isNotEmpty()) return
        append(buildJsonObject {
            put("id", UUID.randomUUID().toString())
            put("deviceId", "web")
            put("ts", clock())
            put("entity", entity)
            put("key", key)
            put("action", action)
            put("value", value ?: JsonNull)
        })
    }

    private fun append(op: JsonObject): Boolean {
        val id = op["id"]!!.jsonPrimitive.content
        if (!operationIds.add(id)) return false
        val seq = nextSeq++
        operations += Op(seq, JsonObject(op + ("seq" to JsonPrimitive(seq))))
        return true
    }

    private fun mangaKey(m: Manga) = "${m.sourceId}|${m.url}"

    // =========================================================================== REST

    private fun rest(req: Request, route: String, device: String): Reply {
        val method = req.method
        val body = req.body
        val query = req.rawQuery.split('&').filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
            java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
        }
        return when {
            route == "health" && method == "GET" -> health()
            route == "chapter/lookup" && method == "GET" -> chapterLookup(query)
            route == "manga/lookup" && method == "GET" -> mangaLookup(query)
            Regex("chapter/(\\d+)/archive").matches(route) -> archive(route.split('/')[1].toInt(), method)
            route == "backups/upload" && method == "POST" -> backupUpload(req, body, query["device"], device)
            route == "backups" && method == "GET" -> reply(200, JsonArray(backups.sortedByDescending { it.createdAt }.map(::backupInfo)))
            Regex("backups/[^/]+/download").matches(route) -> backupDownload(java.net.URLDecoder.decode(route.split('/')[1], "UTF-8"))
            Regex("backups/[^/]+").matches(route) && method == "DELETE" -> backupDelete(java.net.URLDecoder.decode(route.removePrefix("backups/"), "UTF-8"), device)
            route == "merged" && method == "GET" -> reply(200, JsonArray(merged.keys.sorted().mapNotNull { id -> mangas.firstOrNull { it.id == id }?.let(::mergedGroup) }))
            route == "merged" && method == "PUT" -> mergedPutByRef(body, device)
            Regex("manga/(\\d+)/merged").matches(route) -> mergedById(route.split('/')[1].toInt(), method, body, device)
            route == "policy" && method == "GET" -> reply(200, buildJsonObject { put("downloadPolicy", policy) })
            route == "policy" && method == "PUT" -> policyPut(body, device)
            route == "history" && method == "PUT" -> historyPut(body, device)
            route == "deletions" && method == "POST" -> deletionsPost(body, device)
            route == "deletions" && method == "GET" -> deletionsGet(query["afterId"]?.toIntOrNull() ?: 0)
            route == "operations" && !operationLogEnabled -> reply(404, error("no such route: $method $route"))
            route == "operations" && method == "GET" -> operationsGet(query)
            route == "operations" && method == "POST" -> operationsPost(body)
            else -> reply(404, error("no such route: $method $route"))
        }
    }

    private fun health() = reply(
        200,
        buildJsonObject {
            put("status", "ok")
            put("serverVersion", "mock")
            put("apiVersion", 1)
            putJsonObject("database") { put("type", "H2") }
            putJsonObject("storage") {
                put("type", "local")
                put("path", "/mock/downloads")
                put("freeBytes", 1_000_000_000L)
                put("totalBytes", 2_000_000_000L)
            }
        },
    )

    private fun cbz(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            (1..3).forEach { page ->
                zip.putNextEntry(ZipEntry("%03d.png".format(page)))
                zip.write(PNG)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private val cbzBytes by lazy { cbz() }

    private fun findChapter(id: Int): Pair<Manga, Chapter>? =
        mangas.firstNotNullOfOrNull { m -> m.chapters.firstOrNull { it.id == id }?.let { m to it } }

    private fun chapterLookup(q: Map<String, String>): Reply {
        val manga = mangas.firstOrNull { it.sourceId == q["sourceId"] && it.url == q["mangaUrl"] }
        val chapter = manga?.chapters?.firstOrNull { it.url == q["chapterUrl"] } ?: return reply(404, error("Chapter not found"))
        return reply(
            200,
            buildJsonObject {
                put("chapterId", chapter.id)
                put("mangaId", manga.id)
                put("downloaded", chapter.downloaded)
                put("sizeBytes", if (chapter.downloaded) cbzBytes.size.toLong() else -1L)
            },
        )
    }

    private fun mangaLookup(q: Map<String, String>): Reply {
        val manga = mangas.firstOrNull { it.sourceId == q["sourceId"] && it.url == q["url"] } ?: return reply(404, error("Manga not found"))
        return reply(
            200,
            buildJsonObject {
                put("mangaId", manga.id)
                put("title", manga.url)
                putJsonArray("chapters") {
                    manga.chapters.forEachIndexed { index, c ->
                        add(
                            buildJsonObject {
                                put("chapterId", c.id)
                                put("url", c.url)
                                put("chapterNumber", (index + 1).toFloat())
                                put("downloaded", c.downloaded)
                                put("sizeBytes", -1L)
                            },
                        )
                    }
                }
            },
        )
    }

    private fun archive(id: Int, method: String): Reply {
        val chapter = findChapter(id)?.second
        if (chapter == null || !chapter.downloaded) return reply(404, error("Chapter $id is not downloaded on the server"))
        val headers = mapOf("Content-Disposition" to "attachment; filename=\"chapter-$id.cbz\"")
        // a HEAD announces the length it would have sent
        return Reply(200, "application/vnd.comicbook+zip", if (method == "HEAD") ByteArray(0) else cbzBytes, if (method == "HEAD") headers + ("Content-Length" to cbzBytes.size.toString()) else headers)
    }

    // --- backups

    private fun backupInfo(b: Backup) = buildJsonObject {
        put("id", b.id)
        put("name", b.name)
        put("sizeBytes", b.bytes.size.toLong())
        put("createdAt", b.createdAt)
        put("device", b.device?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun backupUpload(req: Request, body: ByteArray, deviceQuery: String?, device: String): Reply {
        val contentType = req.headers["content-type"].orEmpty()
        val boundary = contentType.substringAfter("boundary=", "").trim('"')
        if (boundary.isEmpty()) return reply(400, error("Multipart field 'file' is required"))
        val delimiter = "--$boundary".toByteArray()
        val text = String(body, Charsets.ISO_8859_1)
        val start = text.indexOf("--$boundary")
        if (start < 0) return reply(400, error("Multipart field 'file' is required"))
        // the part with filename=, headers end at the first blank line, content ends before the next delimiter
        val partStart = text.indexOf("filename=\"", start)
        if (partStart < 0) return reply(400, error("Multipart field 'file' is required"))
        val name = text.substring(partStart + 10, text.indexOf('"', partStart + 10))
        val contentStart = text.indexOf("\r\n\r\n", partStart) + 4
        val contentEnd = text.indexOf("\r\n--$boundary", contentStart)
        val bytes = body.copyOfRange(contentStart, contentEnd)
        val created = clock()
        val id = "${created}__${(deviceQuery ?: "")}__$name"
        val backup = Backup(id, name, deviceQuery?.takeIf { it.isNotEmpty() }, created, bytes)
        backups += backup
        record(device, "BACKUP", "backup:$id", "SET", buildJsonObject { put("device", deviceQuery ?: ""); put("sizeBytes", bytes.size.toLong()) })
        check(delimiter.isNotEmpty())
        return reply(201, backupInfo(backup))
    }

    private fun backupDownload(id: String): Reply {
        val backup = backups.firstOrNull { it.id == id } ?: return reply(404, error("Backup not found"))
        return Reply(200, "application/octet-stream", backup.bytes)
    }

    private fun backupDelete(id: String, device: String): Reply {
        val backup = backups.firstOrNull { it.id == id } ?: return reply(404, error("Backup not found"))
        backups.remove(backup)
        record(device, "BACKUP", "backup:$id", "REMOVE", null)
        return Reply(204, "application/json", ByteArray(0))
    }

    // --- merged sources

    private fun mergedGroup(m: Manga) = buildJsonObject {
        put("mangaId", m.id)
        putJsonObject("primary") { put("sourceId", m.sourceId); put("url", m.url) }
        put("sources", JsonArray(merged[m.id].orEmpty()))
    }

    private fun mergedReplace(m: Manga, sources: JsonArray, device: String): Reply {
        val cleaned = sources.map { it.jsonObject }
            .filter { it["sourceId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() != null }
            .filter { it["sourceId"]!!.jsonPrimitive.content != m.sourceId && it["url"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true }
            .distinctBy { it["sourceId"]!!.jsonPrimitive.content }
            .sortedBy { it["priority"]?.jsonPrimitive?.intOrNull ?: 0 }
            .mapIndexed { index, o ->
                buildJsonObject {
                    put("sourceId", o["sourceId"]!!.jsonPrimitive.content)
                    put("url", o["url"]!!.jsonPrimitive.content)
                    put("priority", index)
                    put("updatesEnabled", o["updatesEnabled"]?.jsonPrimitive?.booleanOrNull ?: true)
                }
            }
        val before = merged[m.id].orEmpty()
        if (cleaned.isEmpty()) merged.remove(m.id) else merged[m.id] = cleaned
        if (before != cleaned) {
            record(device, "MERGED", "merged:${mangaKey(m)}", "SET", buildJsonObject { put("sources", JsonArray(cleaned)) })
        }
        return reply(200, mergedGroup(m))
    }

    private fun mergedPutByRef(body: ByteArray, device: String): Reply {
        val json = JSON.parseToJsonElement(String(body)).jsonObject
        val primary = json["primary"]!!.jsonObject
        val manga = mangas.firstOrNull { it.sourceId == primary["sourceId"]!!.jsonPrimitive.content && it.url == primary["url"]!!.jsonPrimitive.content }
            ?: return reply(404, error("Primary manga is not known by the server"))
        return mergedReplace(manga, json["sources"]?.jsonArray ?: JsonArray(emptyList()), device)
    }

    private fun mergedById(id: Int, method: String, body: ByteArray, device: String): Reply {
        val manga = mangas.firstOrNull { it.id == id } ?: return reply(404, error("Manga not found"))
        return if (method == "PUT") {
            mergedReplace(manga, JSON.parseToJsonElement(String(body)).jsonObject["sources"]?.jsonArray ?: JsonArray(emptyList()), device)
        } else {
            reply(200, mergedGroup(manga))
        }
    }

    // --- policy, history, deletions

    private fun policyPut(body: ByteArray, device: String): Reply {
        val value = runCatching { JSON.parseToJsonElement(String(body)).jsonObject["downloadPolicy"]!!.jsonPrimitive.content }.getOrNull()
            ?: return reply(400, error("Invalid JSON body"))
        if (value !in setOf("SERVER_ONLY", "SERVER_AND_CLIENTS")) {
            return reply(400, error("downloadPolicy must be one of [SERVER_ONLY, SERVER_AND_CLIENTS]"))
        }
        if (value != policy) record(device, "POLICY", "policy", "SET", buildJsonObject { put("downloadPolicy", value) })
        policy = value
        return reply(200, buildJsonObject { put("downloadPolicy", policy) })
    }

    private fun historyPut(body: ByteArray, device: String): Reply {
        val entries = JSON.parseToJsonElement(String(body)).jsonObject["entries"]?.jsonArray ?: JsonArray(emptyList())
        var updated = 0
        for (entry in entries.map { it.jsonObject }) {
            val (manga, chapter) = findChapter(entry["chapterId"]!!.jsonPrimitive.int) ?: continue
            val at = entry["lastReadAt"]!!.jsonPrimitive.long.coerceAtLeast(0)
            if (chapter.lastReadAt != at) {
                chapter.lastReadAt = at
                record(
                    device, "HISTORY", "history:${mangaKey(manga)}|${chapter.url}", if (at == 0L) "REMOVE" else "SET",
                    if (at == 0L) null else buildJsonObject { put("lastReadAt", at) },
                )
            }
            updated++
        }
        return reply(200, buildJsonObject { put("updated", updated) })
    }

    private fun deletionsPost(body: ByteArray, device: String): Reply {
        val json = JSON.parseToJsonElement(String(body)).jsonObject
        val ids = json["chapterIds"]?.jsonArray?.map { it.jsonPrimitive.int }.orEmpty()
        val deleteFromServer = json["deleteFromServer"]?.jsonPrimitive?.booleanOrNull ?: false
        var queued = 0
        for (id in ids) {
            val (manga, chapter) = findChapter(id) ?: continue
            tombstones += Tombstone(nextTombstone++, manga.sourceId, manga.url, chapter.url, (manga.chapters.indexOf(chapter) + 1).toFloat(), clock())
            if (deleteFromServer) chapter.downloaded = false
            // asking again for the same thing is not a new fact
            val key = "deletion:${mangaKey(manga)}|${chapter.url}"
            val last = operations.lastOrNull { it.json["key"]!!.jsonPrimitive.content == key }
            if (last?.json?.get("value")?.jsonObject?.get("deleteFromServer")?.jsonPrimitive?.booleanOrNull != deleteFromServer) {
                record(device, "DELETION", key, "SET", buildJsonObject { put("deleteFromServer", deleteFromServer) })
            }
            queued++
        }
        return reply(200, buildJsonObject { put("queued", queued) })
    }

    private fun deletionsGet(afterId: Int) = reply(
        200,
        JsonArray(
            tombstones.filter { it.id > afterId }.map {
                buildJsonObject {
                    put("id", it.id)
                    put("sourceId", it.sourceId)
                    put("mangaUrl", it.mangaUrl)
                    put("chapterUrl", it.chapterUrl)
                    put("chapterNumber", it.chapterNumber)
                    put("requestedAt", it.requestedAt)
                }
            },
        ),
    )

    // --- operation log

    private fun operationsGet(q: Map<String, String>): Reply {
        val after = q["afterSeq"]?.toLongOrNull() ?: 0
        val limit = (q["limit"]?.toIntOrNull() ?: 1000).coerceIn(1, 5000)
        val entity = q["entity"]?.takeIf { it.isNotEmpty() }
        val page = operations.filter { it.seq > after && (entity == null || it.json["entity"]!!.jsonPrimitive.content == entity) }.take(limit)
        return reply(
            200,
            buildJsonObject {
                put("operations", JsonArray(page.map { it.json }))
                put("lastSeq", page.lastOrNull()?.seq ?: after)
                put("serverTime", clock())
            },
        )
    }

    private fun operationsPost(body: ByteArray): Reply {
        val ops = JSON.parseToJsonElement(String(body)).jsonObject["operations"]?.jsonArray ?: JsonArray(emptyList())
        var accepted = 0
        var duplicates = 0
        for (op in ops.map { it.jsonObject }) if (append(op)) accepted++ else duplicates++
        return reply(200, buildJsonObject { put("accepted", accepted); put("duplicates", duplicates) })
    }

    // =========================================================================== GraphQL

    private fun graphql(device: String, raw: String): Reply {
        val request = JSON.parseToJsonElement(raw).jsonObject
        val query = request["query"]!!.jsonPrimitive.content
        val variables = request["variables"]?.jsonObject ?: JsonObject(emptyMap())
        val data = when {
            "KarasuLibrary" in query -> library(variables)
            "KarasuCategories" in query -> buildJsonObject { putJsonObject("categories") { put("nodes", categoryNodes()) } }
            "KarasuCreateCategory" in query -> createCategory(variables["name"]!!.jsonPrimitive.content, device)
            "KarasuRenameCategory" in query -> renameCategory(variables["id"]!!.jsonPrimitive.int, variables["name"]!!.jsonPrimitive.content, device)
            "KarasuUpdateMangaCategories" in query -> updateMangaCategories(variables, device)
            "KarasuUpdateChapters" in query -> updateChapters(variables, device)
            "deleteCategory" in query -> deleteCategory(Regex("categoryId:\\s*(\\d+)").find(query)!!.groupValues[1].toInt(), device)
            else -> return reply(200, buildJsonObject { put("errors", buildJsonArray { add(buildJsonObject { put("message", "mock: unknown operation") }) }) })
        }
        return reply(200, buildJsonObject { put("data", data) })
    }

    private fun categoryNodes() = JsonArray(categories.sortedBy { it.order }.map(::categoryJson))

    private fun categoryJson(c: Category) = buildJsonObject {
        put("id", c.id)
        put("name", c.name)
        put("order", c.order)
        put("default", c.isDefault)
    }

    private fun createCategory(name: String, device: String): JsonObject {
        val category = Category(nextCategoryId++, name, nextCategoryId)
        categories += category
        record(device, "CATEGORY", "category:${category.id}", "SET", buildJsonObject { put("name", name) })
        return buildJsonObject { putJsonObject("createCategory") { put("category", categoryJson(category)) } }
    }

    private fun renameCategory(id: Int, name: String, device: String): JsonObject {
        val category = categories.first { it.id == id }
        if (category.name != name) {
            category.name = name
            record(device, "CATEGORY", "category:$id", "SET", buildJsonObject { put("name", name) })
        }
        return buildJsonObject { putJsonObject("updateCategory") { put("category", categoryJson(category)) } }
    }

    private fun deleteCategory(id: Int, device: String): JsonObject {
        categories.removeAll { it.id == id }
        // the real server logs only the category going away, not each link it took with it
        mangas.forEach { it.categoryIds.remove(id) }
        record(device, "CATEGORY", "category:$id", "REMOVE", null)
        return buildJsonObject { putJsonObject("deleteCategory") { putJsonObject("category") { put("id", id) } } }
    }

    private fun updateMangaCategories(v: JsonObject, device: String): JsonObject {
        val manga = mangas.first { it.id == v["id"]!!.jsonPrimitive.int }
        v["add"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.int }.orEmpty().forEach {
            if (manga.categoryIds.add(it)) record(device, "CATEGORY_LINK", "link:${mangaKey(manga)}|$it", "SET", null)
        }
        v["remove"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.int }.orEmpty().forEach {
            if (manga.categoryIds.remove(it)) record(device, "CATEGORY_LINK", "link:${mangaKey(manga)}|$it", "REMOVE", null)
        }
        return buildJsonObject { putJsonObject("updateMangaCategories") { put("clientMutationId", JsonNull) } }
    }

    private fun updateChapters(v: JsonObject, device: String): JsonObject {
        var index = 0
        while (v.containsKey("id$index")) {
            val (manga, chapter) = findChapter(v["id$index"]!!.jsonPrimitive.int)!!
            val patch = v["patch$index"]!!.jsonObject
            var changed = false
            patch["isRead"]?.takeIf { it !is JsonNull }?.let {
                val read = it.jsonPrimitive.boolean
                if (chapter.read != read) { chapter.read = read; changed = true }
            }
            patch["lastPageRead"]?.takeIf { it !is JsonNull }?.let {
                // stored rounded down to the pages the server knows (none, for a chapter it has not downloaded)
                val page = it.jsonPrimitive.int.coerceAtMost(chapter.pageCount).coerceAtLeast(0)
                if (chapter.lastPageRead != page) { chapter.lastPageRead = page; changed = true }
                chapter.lastReadAt = clock() / 1000 // the stamp a page change always leaves
            }
            if (changed) {
                record(
                    device, "PROGRESS", "progress:${mangaKey(manga)}|${chapter.url}", "SET",
                    buildJsonObject { put("read", chapter.read); put("lastPageRead", chapter.lastPageRead) },
                )
            }
            index++
        }
        return buildJsonObject { /* the document only asks for clientMutationId */ }
    }

    private fun library(v: JsonObject): JsonObject {
        val first = v["first"]!!.jsonPrimitive.int
        val offset = v["offset"]!!.jsonPrimitive.int
        val page = mangas.drop(offset).take(first)
        return buildJsonObject {
            putJsonObject("mangas") {
                put(
                    "nodes",
                    JsonArray(
                        page.map { m ->
                            buildJsonObject {
                                put("id", m.id)
                                put("sourceId", m.sourceId)
                                put("url", m.url)
                                putJsonObject("categories") { put("nodes", JsonArray(m.categoryIds.map { buildJsonObject { put("id", it) } })) }
                                putJsonObject("chapters") {
                                    put(
                                        "nodes",
                                        JsonArray(
                                            m.chapters.map { c ->
                                                buildJsonObject {
                                                    put("id", c.id)
                                                    put("url", c.url)
                                                    put("isRead", c.read)
                                                    put("lastPageRead", c.lastPageRead)
                                                    put("lastReadAt", c.lastReadAt)
                                                }
                                            },
                                        ),
                                    )
                                }
                            }
                        },
                    ),
                )
                putJsonObject("pageInfo") { put("hasNextPage", offset + first < mangas.size) }
            }
        }
    }
}
