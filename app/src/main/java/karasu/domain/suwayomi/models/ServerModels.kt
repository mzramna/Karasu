package karasu.domain.suwayomi.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire types of the Karasu REST contract (`/api/v1/karasu`, version 1).
 *
 * Everything here is `ignoreUnknownKeys`-friendly on purpose: the server is a separate project
 * and adding a field there must never break an older app.
 */

/**
 * A source id travels as a string because a 64-bit id does not fit in a JavaScript number; the
 * server contract says so. Accepting a bare number too costs nothing and survives a server that
 * forgets, so the model always holds a string and callers convert with `toLongOrNull()`.
 */
object LenientIdSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientId", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String =
        (decoder as? JsonDecoder)?.decodeJsonElement()?.jsonPrimitive?.content ?: decoder.decodeString()

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

@Serializable
data class ServerHealth(
    val status: String = "",
    val serverVersion: String = "",
    val apiVersion: Int = 0,
    val database: ServerDatabase = ServerDatabase(),
    val storage: ServerStorage = ServerStorage(),
)

@Serializable
data class ServerDatabase(val type: String = "")

@Serializable
data class ServerStorage(
    /** `local` or `nfs`. */
    val type: String = "",
    val path: String = "",
    val freeBytes: Long = 0,
    val totalBytes: Long = 0,
)

@Serializable
data class ChapterLookup(
    val chapterId: Long,
    val mangaId: Long = 0,
    val downloaded: Boolean = false,
    val sizeBytes: Long = 0,
)

@Serializable
data class MangaLookup(
    val mangaId: Long,
    val title: String = "",
    val chapters: List<MangaLookupChapter> = emptyList(),
)

@Serializable
data class MangaLookupChapter(
    val chapterId: Long,
    val url: String,
    val chapterNumber: Double = -1.0,
    val downloaded: Boolean = false,
    val sizeBytes: Long = 0,
)

@Serializable
data class BackupInfo(
    /** The file name; the only handle the server gives for download and delete. */
    val id: String,
    val name: String = id,
    val sizeBytes: Long = 0,
    val createdAt: Long = 0,
    val device: String? = null,
)

@Serializable
data class MergedSource(
    @Serializable(with = LenientIdSerializer::class) val sourceId: String,
    val url: String,
    /** 0 is the highest priority. */
    val priority: Int = 0,
    /** False marks a reserve: only asked for pages when the others fail. */
    val updatesEnabled: Boolean = true,
)

@Serializable
data class MangaRef(
    @Serializable(with = LenientIdSerializer::class) val sourceId: String,
    val url: String,
)

@Serializable
data class MergedGroup(
    val mangaId: Long = 0,
    val primary: MangaRef,
    val sources: List<MergedSource> = emptyList(),
)

@Serializable
data class MergedSourcesBody(val sources: List<MergedSource>)

@Serializable
data class PutMergedBody(val primary: MangaRef, val sources: List<MergedSource>)

enum class DownloadPolicy {
    /** Only the server fetches chapters from sources; clients should take them from it. */
    SERVER_ONLY,

    /** Clients may also download from the sources on their own. */
    SERVER_AND_CLIENTS,
    ;

    companion object {
        /** An unknown value is treated as the permissive one, so a newer server never blocks downloads. */
        fun parse(raw: String?): DownloadPolicy =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SERVER_AND_CLIENTS
    }
}

@Serializable
data class PolicyBody(val downloadPolicy: String)

@Serializable
data class DeletionRequest(val chapterIds: List<Long>, val deleteFromServer: Boolean)

@Serializable
data class DeletionQueued(val queued: Int = 0)

/** A "tombstone": the server recording that a chapter was deleted, and how to find it elsewhere. */
@Serializable
data class DeletionEntry(
    val id: Long,
    @Serializable(with = LenientIdSerializer::class) val sourceId: String,
    val mangaUrl: String,
    val chapterUrl: String,
    val chapterNumber: Double = -1.0,
    val requestedAt: Long = 0,
)

@Serializable
data class ApiError(val error: String = "")
