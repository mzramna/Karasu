package karasu.domain.suwayomi

import karasu.domain.suwayomi.models.MergedSource
import kotlin.math.max

/**
 * The reconcile rules, free of I/O so they can be tested on their own.
 *
 * The sync is client-driven and the server keeps no per-device state, so "what changed" has to be
 * worked out here. For anything that can be removed, that takes a *common ancestor*: what both
 * sides looked like after the last successful sync. Without it a deletion on one side is
 * indistinguishable from an addition on the other, and the next pull would silently undo it.
 */
object SyncMath {

    /**
     * Three-way merge of two sets that both started from [base].
     *
     * An element survives when it is on both sides, or when one side added it. It is dropped when
     * either side removed it — so a deletion propagates, and an addition is never lost. With an
     * empty [base] (first sync) this is a plain union.
     */
    fun <T> mergeSets(base: Set<T>, local: Set<T>, remote: Set<T>): Set<T> =
        (local + remote) - (base - local) - (base - remote)

    /** A chapter's reading state as one side knows it. [lastPageRead] is the 0-based page. */
    data class Progress(val read: Boolean, val lastPageRead: Int)

    /**
     * Reading only moves forward: a chapter read on either side is read, and the furthest page wins.
     *
     * The price is that marking a chapter *unread* on one device does not spread — the next sync
     * sees it read on the other side and reads it again. Undoing needs a timestamp per change,
     * which Karasu's chapter rows do not carry; forward-only is the safe failure mode, since the
     * worst case is a chapter marked read that should not be, never a lost place.
     */
    fun mergeProgress(local: Progress, remote: Progress): Progress =
        Progress(local.read || remote.read, max(local.lastPageRead, remote.lastPageRead))

    /**
     * One extra source of a merged manga, reduced to what identifies and configures it.
     * Order in a list is the priority; absolute numbers are not compared (see [normalizeMerged]).
     */
    data class MergedEntry(val sourceId: String, val url: String, val updatesEnabled: Boolean) {
        val key: String get() = "$sourceId|$url"
    }

    fun List<MergedSource>.toEntries(): List<MergedEntry> =
        sortedBy { it.priority }.map { MergedEntry(it.sourceId, it.url, it.updatesEnabled) }

    /** What to do with a merged group after comparing both sides with their last agreed state. */
    data class MergedOutcome(
        val result: List<MergedEntry>,
        val changeLocal: Boolean,
        val changeRemote: Boolean,
    )

    /**
     * Reconciles the extra sources of one manga.
     *
     * [base] is the list after the last sync, or null when there was none. Order and the
     * reserve flag matter, so a reorder on one side is carried over as long as the other side did
     * not also touch the group; when both did, entries are merged as a set and the local order wins.
     */
    fun mergeMerged(base: List<MergedEntry>?, local: List<MergedEntry>, remote: List<MergedEntry>): MergedOutcome {
        val result = when {
            local == remote -> local
            base != null && local == base -> remote
            base != null && remote == base -> local
            else -> {
                val keys = mergeSets(
                    base.orEmpty().mapTo(mutableSetOf()) { it.key },
                    local.mapTo(mutableSetOf()) { it.key },
                    remote.mapTo(mutableSetOf()) { it.key },
                )
                // Local entries first, in their own order and with their own flags; what only the
                // server has follows in the server's order.
                local.filter { it.key in keys } + remote.filter { it.key in keys && local.none { l -> l.key == it.key } }
            }
        }
        return MergedOutcome(result, changeLocal = result != local, changeRemote = result != remote)
    }

    /** A source id is a Long locally and a string on the wire; null when the string is not one. */
    fun parseSourceId(raw: String): Long? = raw.trim().toLongOrNull()

    /** The key a manga is matched by on both sides. Never an id: those are different on each side. */
    fun mangaKey(sourceId: Long, url: String) = "$sourceId|$url"
    fun mangaKey(sourceId: String, url: String) = "${sourceId.trim()}|$url"

    /** Category names compare ignoring case and surrounding spaces, so "Action " and "action" are one. */
    fun categoryKey(name: String) = name.trim().lowercase()
}
