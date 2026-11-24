package karasu.domain.operations

import karasu.domain.suwayomi.ServerPreferences
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Entity names as the server's operation log spells them (contract v2). */
object OpEntity {
    const val CATEGORY = "CATEGORY"
    const val CATEGORY_LINK = "CATEGORY_LINK"
    const val PROGRESS = "PROGRESS"
    const val HISTORY = "HISTORY"
    const val MERGED = "MERGED"
    const val DELETION = "DELETION"

    /** An extension repository; [LocalOp.name] holds its base url. */
    const val EXTENSION_REPO = "EXTENSION_REPO"
}

object OpAction {
    const val SET = "SET"
    const val REMOVE = "REMOVE"
}

/**
 * One thing the user did on this device, with the moment they did it.
 *
 * It points at local rows ([chapterId], [mangaId], [categoryId]) because those are all the code that
 * writes them has at hand; the sync turns them into the identity the server and the other devices use
 * (source + urls, server category id) when it publishes. [ts] is epoch milliseconds of the *action*,
 * not of the sync: it is what decides which side's change is the real one when two collide.
 */
@Serializable
data class LocalOp(
    val id: String,
    val ts: Long,
    val entity: String,
    val action: String,
    val chapterId: Long? = null,
    val mangaId: Long? = null,
    val categoryId: Long? = null,
    /** The category name at the time (needed to describe a deletion or a rename). */
    val name: String? = null,
    val published: Boolean = false,
) {
    /** What the operation is about, so a newer one on the same thing replaces an unpublished older one. */
    val target: String
        get() = if (entity == OpEntity.EXTENSION_REPO) "$entity|$name" else "$entity|${chapterId ?: ""}|${mangaId ?: ""}|${categoryId ?: ""}"
}

/**
 * Marks work done by the sync itself. The sync writes through the same interactors the screens use, and
 * what it writes is somebody else's change arriving, not something this device's user did: recording it
 * would make every pulled change look like a fresh local edit with the time of the sync.
 *
 * A coroutine context element rather than a flag, so a user tapping something while a sync runs on
 * another coroutine is still recorded.
 */
class SyncOrigin : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SyncOrigin>
}

/**
 * This device's log of operations. The interactors that write the data the sync cares about call [record];
 * the sync reads [pending], publishes it, and consults [latest] when both sides changed the same thing.
 */
class OperationLog(
    private val preferences: ServerPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val serializer = ListSerializer(LocalOp.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    /** The time of this device, which tests move around to say "I did this a while ago". */
    fun now(): Long = clock()

    suspend fun record(
        entity: String,
        action: String,
        chapterId: Long? = null,
        mangaId: Long? = null,
        categoryId: Long? = null,
        name: String? = null,
    ) {
        if (currentCoroutineContext()[SyncOrigin] != null) return
        val op = LocalOp(UUID.randomUUID().toString(), clock(), entity, action, chapterId, mangaId, categoryId, name)
        synchronized(lock) {
            val all = load().filterNot { !it.published && it.target == op.target } + op
            save(all)
        }
    }

    fun all(): List<LocalOp> = synchronized(lock) { load() }

    fun pending(): List<LocalOp> = all().filterNot { it.published }.sortedBy { it.ts }

    fun markPublished(ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(lock) { save(load().map { if (it.id in ids) it.copy(published = true) else it }) }
    }

    /** When this device last did something to the thing, or null if nothing is on record. */
    fun latest(
        entity: String,
        chapterId: Long? = null,
        mangaId: Long? = null,
        categoryId: Long? = null,
        name: String? = null,
    ): Long? =
        all().filter {
            it.entity == entity &&
                (chapterId == null || it.chapterId == chapterId) &&
                (mangaId == null || it.mangaId == mangaId) &&
                (categoryId == null || it.categoryId == categoryId) &&
                (name == null || it.name == name)
        }.maxOfOrNull { it.ts }

    /** Old entries only help to settle a conflict, and a conflict cannot reach back past the last sync. */
    fun prune(olderThanMillis: Long = RETENTION_MS) {
        val limit = clock() - olderThanMillis
        synchronized(lock) { save(load().filter { !it.published || it.ts >= limit }.takeLast(MAX_ENTRIES)) }
    }

    private fun load(): List<LocalOp> {
        val raw = preferences.operationLog().get()
        if (raw.isBlank()) return emptyList()
        return try {
            json.decodeFromString(serializer, raw)
        } catch (_: Exception) {
            emptyList() // an unreadable log is a lost log, not a reason to stop the app
        }
    }

    private fun save(ops: List<LocalOp>) {
        preferences.operationLog().set(json.encodeToString(serializer, ops.takeLast(MAX_ENTRIES)))
    }

    private companion object {
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_ENTRIES = 3000
    }
}
