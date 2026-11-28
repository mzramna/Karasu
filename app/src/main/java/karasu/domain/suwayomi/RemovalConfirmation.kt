package karasu.domain.suwayomi

import karasu.domain.suwayomi.interactor.SyncSuwayomiServer
import karasu.domain.suwayomi.models.PlannedRemoval
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The conversation between the sync and the user about removals.
 *
 * A sync that finds something to remove does not do it ([SyncSuwayomiServer.await] with an approval set): it reports the
 * removal, and this keeps the list until the user answers. "Remove" approves exactly the removals on the list, by their
 * keys, and the next sync does those and only those. "Not now" is remembered per list, so the same list is not asked
 * about every time the periodic sync runs; a different list is asked about again.
 */
class RemovalConfirmation(private val preferences: ServerPreferences) {

    private val json = Json { ignoreUnknownKeys = true }
    private val removals = ListSerializer(PlannedRemoval.serializer())
    private val keys = SetSerializer(String.serializer())

    /** What the next sync may remove: everything when confirmation is off, otherwise only what the user approved. */
    fun approvedForNextRun(): Set<String>? {
        if (!preferences.confirmRemovals().get()) return null
        val raw = preferences.approvedRemovals().get()
        if (raw.isBlank()) return emptySet()
        return try {
            json.decodeFromString(keys, raw)
        } catch (_: Exception) {
            emptySet() // an unreadable approval approves nothing
        }
    }

    /** Called when a sync has run to the end: what it held becomes the pending list, and the approval it used is spent. */
    fun recordRun(summary: SyncSuwayomiServer.Summary) {
        preferences.approvedRemovals().set("")
        val held = summary.heldRemovals
        if (held.isEmpty()) {
            preferences.pendingRemovals().set("")
            preferences.declinedRemovals().set("")
        } else {
            preferences.pendingRemovals().set(json.encodeToString(removals, held))
        }
    }

    fun pending(): List<PlannedRemoval> {
        val raw = preferences.pendingRemovals().get()
        if (raw.isBlank()) return emptyList()
        return try {
            json.decodeFromString(removals, raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** True when there is a list the user has not answered: nothing pending, or the same list already declined, is not. */
    fun needsAnswer(): Boolean {
        val list = pending()
        return list.isNotEmpty() && fingerprint(list) != preferences.declinedRemovals().get()
    }

    /** "Remove": the next sync may do exactly the pending removals. */
    fun approve() {
        val list = pending()
        if (list.isEmpty()) return
        preferences.approvedRemovals().set(json.encodeToString(keys, list.mapTo(linkedSetOf()) { it.key }))
        preferences.declinedRemovals().set("")
    }

    /** "Not now": the list stays pending (nothing is removed) but is not asked about again unless it changes. */
    fun decline() {
        val list = pending()
        if (list.isNotEmpty()) preferences.declinedRemovals().set(fingerprint(list))
    }

    private fun fingerprint(list: List<PlannedRemoval>): String =
        list.map { it.key }.sorted().joinToString("\n").hashCode().toString() + ":" + list.size
}
