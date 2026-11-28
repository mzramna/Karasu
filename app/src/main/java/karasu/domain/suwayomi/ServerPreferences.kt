package karasu.domain.suwayomi

import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.core.preference.PreferenceStore

/**
 * Settings for syncing with a self-hosted Suwayomi-Server.
 *
 * Nothing runs until [enabled] is on and [primaryUrl] is filled in, so a fresh install never
 * opens a connection. The server can be reached at two addresses — typically the LAN one and a
 * Tailscale/VPN one — which is why there are two URLs and not one.
 */
class ServerPreferences(private val preferenceStore: PreferenceStore) {

    /** Master switch. Off means every trigger (app start, reader close, periodic) is a no-op. */
    fun enabled() = preferenceStore.getBoolean("suwayomi_enabled", false)

    /** Address tried first, e.g. `http://192.168.1.10:4567`. Meant to be the LAN address. */
    fun primaryUrl() = preferenceStore.getString("suwayomi_primary_url", "")

    /** Address tried when the primary does not answer, e.g. a Tailscale name. Blank means none. */
    fun secondaryUrl() = preferenceStore.getString("suwayomi_secondary_url", "")

    /** Basic Auth user. Blank means the server has no authentication. */
    fun username() = preferenceStore.getString("suwayomi_username", "")

    /** Basic Auth password. Private: kept out of backups, like the other stored credentials. */
    fun password() = preferenceStore.getString(Preference.privateKey("suwayomi_password"), "")

    /**
     * Ask the server for a chapter before downloading it from the source.
     *
     * Only takes effect when the server is reachable and says it already has the chapter; any
     * failure falls back to the normal download.
     */
    fun preferServerDownload() = preferenceStore.getBoolean("suwayomi_prefer_server_download", false)

    /** Hours between automatic syncs. 0 turns the periodic job off, leaving manual sync. */
    fun syncInterval() = preferenceStore.getInt("suwayomi_sync_interval", 12)

    /** Sync is small JSON, but a first run walks the whole library, so metered data is opt-in. */
    fun onlyOverWifi() = preferenceStore.getBoolean("suwayomi_only_over_wifi", false)

    fun syncCategories() = preferenceStore.getBoolean("suwayomi_sync_categories", true)

    fun syncProgress() = preferenceStore.getBoolean("suwayomi_sync_progress", true)

    /** What was read when, and removing a chapter from the history, in both directions. */
    fun syncHistory() = preferenceStore.getBoolean("suwayomi_sync_history", true)

    fun syncMergedSources() = preferenceStore.getBoolean("suwayomi_sync_merged", true)

    /** Whether chapters deleted from the server's side are also deleted from this device. */
    fun applyRemoteDeletions() = preferenceStore.getBoolean("suwayomi_apply_deletions", true)

    /** Whether automatic backups are also uploaded to the server. Manual ones ask per backup. */
    /**
     * Tell the server (and through it, the other devices) when a downloaded chapter is deleted here.
     * Off by default: deleting something to free space on a phone should not empty the tablet.
     */
    fun pushLocalDeletions() = preferenceStore.getBoolean("suwayomi_push_local_deletions", false)

    fun uploadAutoBackups() = preferenceStore.getBoolean("suwayomi_upload_auto_backups", false)

    /** Default for the "also send to the server" box when creating a backup by hand. */
    fun uploadManualBackups() = preferenceStore.getBoolean("suwayomi_upload_manual_backups", true)

    /**
     * Highest deletion id this device has already applied, or -1 before the first sync.
     *
     * App state rather than a setting: restoring another device's cursor would make this one skip
     * deletions it never saw. The -1 matters: the first run only learns where the log ends,
     * instead of replaying history onto downloads that were made after it.
     */
    fun lastDeletionId() = preferenceStore.getLong(Preference.appStateKey("suwayomi_last_deletion_id"), -1L)

    /** `SERVER_ONLY` or `SERVER_AND_CLIENTS`, as the server last reported it. Blank until the first sync. */
    fun serverDownloadPolicy() = preferenceStore.getString(Preference.appStateKey("suwayomi_download_policy"), "")

    /** Which address answered last (`primary` or `secondary`), so the next run starts there. */
    fun lastGoodOrigin() = preferenceStore.getString(Preference.appStateKey("suwayomi_last_good_origin"), "")

    /**
     * What the merged sources looked like after the last successful sync, per manga, as JSON.
     *
     * Needed to tell "removed on one side" from "added on the other": without a common ancestor a
     * deletion would be undone by the next pull.
     */
    fun mergedSnapshot() = preferenceStore.getString(Preference.appStateKey("suwayomi_merged_snapshot"), "")

    /** The extension repository base urls both sides had after the last sync, as a JSON list; blank until the first. */
    fun extensionRepoSnapshot() = preferenceStore.getString(Preference.appStateKey("suwayomi_extension_repo_snapshot"), "")

    /** Same idea for category links, as `sourceId|url -> category names`. */
    fun categoryLinksSnapshot() =
        preferenceStore.getString(Preference.appStateKey("suwayomi_category_links_snapshot"), "")

    /**
     * `sourceId|url -> chapter url -> packed reading state` of the chapters that are read or past page 0:
     * what both sides agreed on, needed to tell "marked unread" from "never read".
     */
    fun progressSnapshot() = preferenceStore.getString(Preference.appStateKey("suwayomi_progress_snapshot"), "")

    /** `sourceId|url -> chapter url -> last read (epoch seconds)`: the agreed value of each history entry. */
    fun historySnapshot() = preferenceStore.getString(Preference.appStateKey("suwayomi_history_snapshot"), "")

    /** `sourceId|url -> chapter urls` that were downloaded at the end of the last sync. */
    fun downloadedSnapshot() = preferenceStore.getString(Preference.appStateKey("suwayomi_downloaded_snapshot"), "")

    /** `(sourceId, url)` of every manga in this device's library at the end of the last sync. */
    fun librarySnapshot() = preferenceStore.getString(Preference.appStateKey("suwayomi_library_snapshot"), "")

    /** Random and permanent per installation: how the server tells this device's operations from the others'. */
    fun deviceId(): Preference<String> = preferenceStore.getString(Preference.appStateKey("suwayomi_device_id"), "")

    /** The id, made the first time it is asked for. */
    fun ensureDeviceId(): String {
        val pref = deviceId()
        if (pref.get().isBlank()) pref.set(java.util.UUID.randomUUID().toString())
        return pref.get()
    }

    /** Highest operation `seq` of the server's log already looked at. */
    fun lastOperationSeq() = preferenceStore.getLong(Preference.appStateKey("suwayomi_last_operation_seq"), 0L)

    /** This device's own operation log, as JSON (see `OperationLog`). */
    /**
     * Whether a sync that would remove something stops and asks first (on by default). With it off the sync removes what
     * its rules say, as before.
     */
    fun confirmRemovals() = preferenceStore.getBoolean("suwayomi_confirm_removals", true)

    /** The removals the last sync found and did not do, as JSON (a list of `PlannedRemoval`), waiting for the user's answer. */
    fun pendingRemovals() = preferenceStore.getString(Preference.appStateKey("suwayomi_pending_removals"), "")

    /** The keys of the removals the user approved, as JSON: the next sync does exactly these and then forgets them. */
    fun approvedRemovals() = preferenceStore.getString(Preference.appStateKey("suwayomi_approved_removals"), "")

    /** A fingerprint of the pending list the user said "not now" to, so the same list is not asked about again. */
    fun declinedRemovals() = preferenceStore.getString(Preference.appStateKey("suwayomi_declined_removals"), "")

    /**
     * The last server sequence this device had fully applied when a sync finished without errors. It is what the next
     * pull sends as `ack`: the server forgets an operation only when every device has acknowledged it.
     */
    fun operationAckSeq() = preferenceStore.getLong(Preference.appStateKey("suwayomi_operation_ack_seq"), 0L)

    /** Forgets what was agreed on, so the next sync reconciles by state alone and removes nothing (see `resetRequired`). */
    fun clearAgreedState() {
        listOf(mergedSnapshot(), extensionRepoSnapshot(), categoryLinksSnapshot(), progressSnapshot(), historySnapshot(), librarySnapshot())
            .forEach { it.set("") }
    }

    fun operationLog() = preferenceStore.getString(Preference.appStateKey("suwayomi_operation_log"), "")

    /** `local category id -> {server id, agreed name}`: how a rename is told from a new category. */
    fun categoryMapSnapshot() = preferenceStore.getString(Preference.appStateKey("suwayomi_category_map"), "")

    fun lastSyncAt() = preferenceStore.getLong(Preference.appStateKey("suwayomi_last_sync_at"), 0L)

    /** Already localised when written, because only the job knows what happened. */
    fun lastSyncSummary() = preferenceStore.getString(Preference.appStateKey("suwayomi_last_sync_summary"), "")
}
