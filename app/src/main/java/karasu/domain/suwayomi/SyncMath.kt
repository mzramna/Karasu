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
     * The two-sided rule, used when there is nothing to compare against or both sides moved: a chapter
     * read on either side is read, and the furthest page wins. Never loses a place.
     */
    fun mergeProgress(local: Progress, remote: Progress): Progress =
        Progress(local.read || remote.read, max(local.lastPageRead, remote.lastPageRead))

    /**
     * Three-way merge of a chapter's reading state against [base], the state both sides agreed on at
     * the last sync. What the base adds is the ability to tell "marked unread here" from "never read
     * here", so unmarking travels like any other change.
     *
     * - Marking a chapter unread on one side makes it unread on the other, and takes the page with it
     *   (an unread chapter starts again), unless the other side has read further since the base:
     *   new reading is never thrown away by an old unmarking.
     * - Everything else keeps the forward-only rule: read on either side is read, furthest page wins.
     *   A page that goes back by itself is not followed, because the server rounds the page it stores
     *   down to the pages it knows about, and following that would eat the user's place.
     * - With no base (first sync) nothing counts as unmarked, so it is a plain union.
     */
    fun mergeProgress(base: Progress, local: Progress, remote: Progress): Progress {
        if (local == remote) return local
        val localUnmarked = base.read && !local.read
        val remoteUnmarked = base.read && !remote.read
        val localAdvanced = local.lastPageRead > base.lastPageRead
        val remoteAdvanced = remote.lastPageRead > base.lastPageRead
        return when {
            localUnmarked && !remoteUnmarked && !remoteAdvanced -> local
            remoteUnmarked && !localUnmarked && !localAdvanced -> remote
            else -> mergeProgress(local, remote)
        }
    }

    /** A chapter's agreed state as one number, so a snapshot of thousands of chapters stays small. */
    fun Progress.pack(): Int = lastPageRead * 2 + (if (read) 1 else 0)

    fun unpackProgress(packed: Int): Progress = Progress(read = packed and 1 == 1, lastPageRead = packed shr 1)

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
                val localKeys = local.mapTo(mutableSetOf()) { it.key }
                local.filter { it.key in keys } + remote.filter { it.key in keys && it.key !in localKeys }
            }
        }
        return MergedOutcome(result, changeLocal = result != local, changeRemote = result != remote)
    }

    /** A source id is a Long locally and a string on the wire; null when the string is not one. */
    data class HistoryOutcome(val result: Long, val changeLocal: Boolean, val changeRemote: Boolean)

    /**
     * Three-way merge of when a chapter was last read (epoch seconds, 0 = not in the history).
     *
     * One side moving away from [base] while the other stays wins, which is what lets "removed from
     * the history" travel (0 is a value like any other). When both moved, the newer read wins and a
     * removal never beats a read: a chapter read again after being removed on the other side should
     * be in the history.
     */
    fun mergeHistory(base: Long, local: Long, remote: Long): HistoryOutcome {
        if (local == remote) return HistoryOutcome(local, changeLocal = false, changeRemote = false)
        val result = when {
            local != base && remote == base -> local
            remote != base && local == base -> remote
            else -> maxOf(local, remote)
        }
        return HistoryOutcome(result, changeLocal = result != local, changeRemote = result != remote)
    }

    fun parseSourceId(raw: String): Long? = raw.trim().toLongOrNull()

    /** The key a manga is matched by on both sides. Never an id: those are different on each side. */
    fun mangaKey(sourceId: Long, url: String) = "$sourceId|$url"
    fun mangaKey(sourceId: String, url: String) = "${sourceId.trim()}|$url"

    /** Category names compare ignoring case and surrounding spaces, so "Action " and "action" are one. */
    fun categoryKey(name: String) = name.trim().lowercase()
}
