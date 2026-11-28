package eu.kanade.tachiyomi.ui.setting.controllers

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.data.suwayomi.ServerSyncJob
import eu.kanade.tachiyomi.ui.setting.SettingsLegacyController
import eu.kanade.tachiyomi.ui.setting.bindTo
import eu.kanade.tachiyomi.ui.setting.editTextPreference
import eu.kanade.tachiyomi.ui.setting.infoPreference
import eu.kanade.tachiyomi.ui.setting.intListPreference
import eu.kanade.tachiyomi.ui.setting.onChange
import eu.kanade.tachiyomi.ui.setting.onClick
import eu.kanade.tachiyomi.ui.setting.preference
import eu.kanade.tachiyomi.ui.setting.preferenceCategory
import eu.kanade.tachiyomi.ui.setting.switchPreference
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withUIContext
import karasu.data.suwayomi.HealthResult
import karasu.data.suwayomi.ServerOrigin
import karasu.data.suwayomi.ServerSyncApi
import eu.kanade.tachiyomi.ui.more.SyncRemovalsDialog
import karasu.domain.suwayomi.RemovalConfirmation
import karasu.domain.suwayomi.ServerPreferences
import karasu.i18n.MR
import karasu.util.lang.getString
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uy.kohesive.injekt.injectLazy
import eu.kanade.tachiyomi.ui.setting.summaryMRes as summaryRes
import eu.kanade.tachiyomi.ui.setting.titleMRes as titleRes

class SettingsSyncController : SettingsLegacyController() {

    private val serverPreferences: ServerPreferences by injectLazy()
    private val serverApi: ServerSyncApi by injectLazy()

    /**
     * Leaving this screen syncs, for the same reason the shelf screen does: every switch here
     * changes what a sync would do, and changing three of them should cost one run, not three.
     */
    override fun onDestroyView(view: View) {
        super.onDestroyView(view)
        ServerSyncJob.startIfAutomatic(applicationContext ?: return)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = MR.strings.suwayomi

        switchPreference {
            bindTo(serverPreferences.enabled())
            titleRes = MR.strings.suwayomi_enable
            summaryRes = MR.strings.suwayomi_enable_summary
            onChange {
                // Written before the job is set up: the periodic job exists only while sync is on,
                // and setupTask reads the switch, which the framework would otherwise store
                // only after this returns.
                serverPreferences.enabled().set(it as Boolean)
                ServerSyncJob.setupTask(context.applicationContext)
                true
            }
        }

        preferenceCategory {
            titleRes = MR.strings.suwayomi_connection

            editTextPreference(activity) {
                bindTo(serverPreferences.primaryUrl())
                titleRes = MR.strings.suwayomi_primary_url
                summary = serverPreferences.primaryUrl().get()
                    .ifBlank { context.getString(MR.strings.suwayomi_primary_url_hint) }
                onChange {
                    summary = (it as String).ifBlank { context.getString(MR.strings.suwayomi_primary_url_hint) }
                    serverPreferences.primaryUrl().set(it)
                    ServerSyncJob.setupTask(context.applicationContext)
                    true
                }
            }
            editTextPreference(activity) {
                bindTo(serverPreferences.secondaryUrl())
                titleRes = MR.strings.suwayomi_secondary_url
                summary = serverPreferences.secondaryUrl().get()
                    .ifBlank { context.getString(MR.strings.suwayomi_secondary_url_summary) }
                onChange {
                    summary = (it as String).ifBlank { context.getString(MR.strings.suwayomi_secondary_url_summary) }
                    true
                }
            }
            editTextPreference(activity) {
                bindTo(serverPreferences.username())
                titleRes = MR.strings.suwayomi_username
                summaryRes = MR.strings.suwayomi_username_summary
            }
            editTextPreference(activity) {
                bindTo(serverPreferences.password())
                titleRes = MR.strings.suwayomi_password
                // A fixed text, never the stored value: this field is a password.
                summaryRes = MR.strings.suwayomi_password_summary
            }
            preference {
                titleRes = MR.strings.suwayomi_test_connection
                onClick { testConnection() }
            }
        }

        preferenceCategory {
            titleRes = MR.strings.suwayomi_what_to_sync

            switchPreference {
                bindTo(serverPreferences.syncCategories())
                titleRes = MR.strings.suwayomi_sync_categories
                summaryRes = MR.strings.suwayomi_sync_categories_summary
            }
            switchPreference {
                bindTo(serverPreferences.syncProgress())
                titleRes = MR.strings.suwayomi_sync_progress
                summaryRes = MR.strings.suwayomi_sync_progress_summary
            }
            switchPreference {
                bindTo(serverPreferences.syncHistory())
                titleRes = MR.strings.suwayomi_sync_history
                summaryRes = MR.strings.suwayomi_sync_history_summary
            }
            switchPreference {
                bindTo(serverPreferences.syncMergedSources())
                titleRes = MR.strings.suwayomi_sync_merged
                summaryRes = MR.strings.suwayomi_sync_merged_summary
            }
            switchPreference {
                bindTo(serverPreferences.applyRemoteDeletions())
                titleRes = MR.strings.suwayomi_apply_deletions
                summaryRes = MR.strings.suwayomi_apply_deletions_summary
            }
            switchPreference {
                bindTo(serverPreferences.pushLocalDeletions())
                titleRes = MR.strings.suwayomi_push_local_deletions
                summaryRes = MR.strings.suwayomi_push_local_deletions_summary
            }
        }

        preferenceCategory {
            titleRes = MR.strings.suwayomi_downloads

            switchPreference {
                bindTo(serverPreferences.preferServerDownload())
                titleRes = MR.strings.suwayomi_prefer_server_download
                summaryRes = MR.strings.suwayomi_prefer_server_download_summary
            }
        }

        preferenceCategory {
            titleRes = MR.strings.suwayomi_backups

            switchPreference {
                bindTo(serverPreferences.uploadAutoBackups())
                titleRes = MR.strings.suwayomi_upload_auto_backups
            }
            switchPreference {
                bindTo(serverPreferences.uploadManualBackups())
                titleRes = MR.strings.suwayomi_upload_manual_backups
                summaryRes = MR.strings.suwayomi_upload_manual_backups_summary
            }
        }

        preferenceCategory {
            titleRes = MR.strings.suwayomi_sync_section

            intListPreference(activity) {
                bindTo(serverPreferences.syncInterval())
                titleRes = MR.strings.suwayomi_sync_interval
                entriesRes = arrayOf(
                    MR.strings.manual,
                    MR.strings.every_6_hours,
                    MR.strings.every_12_hours,
                    MR.strings.daily,
                    MR.strings.weekly,
                )
                entryValues = listOf(0, 6, 12, 24, 168)
                onChange {
                    ServerSyncJob.setupTask(context.applicationContext, it as Int)
                    true
                }
            }
            switchPreference {
                bindTo(serverPreferences.onlyOverWifi())
                titleRes = MR.strings.suwayomi_only_over_wifi
                onChange {
                    serverPreferences.onlyOverWifi().set(it as Boolean)
                    ServerSyncJob.setupTask(context.applicationContext)
                    true
                }
            }
            switchPreference {
                bindTo(serverPreferences.confirmRemovals())
                titleRes = MR.strings.suwayomi_confirm_removals
                summaryRes = MR.strings.suwayomi_confirm_removals_summary
            }
            preference {
                // Removals the sync found and is waiting on: reachable here after "Not now" and in the notification.
                val confirmation = RemovalConfirmation(serverPreferences)
                fun refresh() {
                    val pending = confirmation.pending()
                    isVisible = pending.isNotEmpty()
                    title = context.getString(MR.strings.suwayomi_removals_pending_row, pending.size)
                }
                refresh()
                serverPreferences.pendingRemovals().changes()
                    .onEach { refresh() }
                    .launchIn(viewScope)
                onClick {
                    val host = activity ?: return@onClick
                    SyncRemovalsDialog.show(host, confirmation)
                }
            }
            preference {
                titleRes = MR.strings.suwayomi_sync_now
                summary = lastSyncSummary(context)
                // The job finishes long after this screen was built, so the line follows the
                // preference instead of being a snapshot of it.
                serverPreferences.lastSyncAt().changes()
                    .onEach { summary = lastSyncSummary(context) }
                    .launchIn(viewScope)
                onClick {
                    if (!serverApi.isConfigured) {
                        context.toast(MR.strings.suwayomi_set_url_first)
                        return@onClick
                    }
                    ServerSyncJob.startNow(context.applicationContext)
                    context.toast(MR.strings.suwayomi_sync_started)
                }
            }
            infoPreference(MR.strings.suwayomi_summary)
        }
    }

    private fun lastSyncSummary(context: Context): String {
        val at = serverPreferences.lastSyncAt().get()
        if (at <= 0L) return context.getString(MR.strings.suwayomi_never_synced)
        val relative = DateUtils.getRelativeTimeSpanString(
            at,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        )
        return context.getString(
            MR.strings.suwayomi_last_sync,
            relative,
            serverPreferences.lastSyncSummary().get(),
        )
    }

    private fun testConnection() {
        val context = activity ?: return
        if (!serverApi.isConfigured) {
            context.toast(MR.strings.suwayomi_set_url_first)
            return
        }
        context.toast(MR.strings.suwayomi_testing)
        viewScope.launchIO {
            val (title, message) = try {
                val result = serverApi.health()
                context.getString(MR.strings.suwayomi_status_ok) to describe(context, result)
            } catch (e: Exception) {
                context.getString(MR.strings.suwayomi_test_connection) to context.getString(
                    MR.strings.suwayomi_status_failed,
                    e.message ?: e::class.simpleName.orEmpty(),
                )
            }
            withUIContext {
                activity?.materialAlertDialog()
                    ?.setTitle(title)
                    ?.setMessage(message)
                    ?.setPositiveButton(android.R.string.ok, null)
                    ?.show()
            }
        }
    }

    /** One line per fact the server reported, so a misconfigured storage or database is visible at a glance. */
    private fun describe(context: Context, result: HealthResult): String {
        val lines = mutableListOf<String>()
        lines += context.getString(MR.strings.suwayomi_status_latency, result.latencyMs)
        val origin = when (result.origin) {
            ServerOrigin.PRIMARY -> MR.strings.suwayomi_origin_primary
            ServerOrigin.SECONDARY -> MR.strings.suwayomi_origin_secondary
        }
        lines += context.getString(MR.strings.suwayomi_status_origin, context.getString(origin)) +
            " (${result.baseUrl})"
        result.health.serverVersion.takeIf { it.isNotBlank() }?.let {
            lines += context.getString(MR.strings.suwayomi_status_version, it)
        }
        result.health.database.type.takeIf { it.isNotBlank() }?.let {
            lines += context.getString(MR.strings.suwayomi_status_database, databaseName(it))
        }
        val storage = result.health.storage
        if (storage.type.isNotBlank()) {
            val kind = when (storage.type.lowercase()) {
                "nfs" -> context.getString(MR.strings.suwayomi_storage_nfs)
                "local" -> context.getString(MR.strings.suwayomi_storage_local)
                else -> storage.type
            }
            lines += if (storage.totalBytes > 0) {
                context.getString(
                    MR.strings.suwayomi_status_storage,
                    kind,
                    Formatter.formatShortFileSize(context, storage.freeBytes),
                    Formatter.formatShortFileSize(context, storage.totalBytes),
                )
            } else {
                context.getString(MR.strings.suwayomi_status_storage_unknown, kind)
            }
        }
        return lines.joinToString("\n")
    }

    /** `POSTGRESQL` reads badly; the two values the contract defines get their usual spelling. */
    private fun databaseName(raw: String) = when (raw.uppercase()) {
        "H2" -> "H2"
        "POSTGRESQL" -> "PostgreSQL"
        else -> raw
    }
}
