package eu.kanade.tachiyomi.data.suwayomi

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.system.workManager
import java.util.concurrent.TimeUnit
import karasu.domain.suwayomi.RemovalConfirmation
import karasu.domain.suwayomi.ServerPreferences
import karasu.domain.suwayomi.interactor.SyncSuwayomiServer
import karasu.i18n.MR
import karasu.util.lang.getString
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Reconciles this device with the Suwayomi server: periodically, on demand, and on the events
 * that make the server's copy stale (app start, closing the reader).
 *
 * A failed run is retried with backoff rather than reported. The usual reason is that the phone is
 * away from both addresses, which the user cannot fix and the next run recovers from by itself —
 * the sync is a reconcile, so nothing is lost by running late.
 */
class ServerSyncJob(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val preferences = Injekt.get<ServerPreferences>()
        if (!preferences.enabled().get() || preferences.primaryUrl().get().isBlank()) return Result.success()

        return try {
            val confirmation = RemovalConfirmation(preferences)
            // Removals are done only when the user approved them (or has turned the question off).
            val result = Injekt.get<SyncSuwayomiServer>().await(confirmation.approvedForNextRun())
            confirmation.recordRun(result)
            Logger.i { "Suwayomi synced: $result" }
            record(preferences, describe(result))
            if (confirmation.needsAnswer()) notifyRemovalsWaiting(result.heldRemovals.size)
            Result.success()
        } catch (e: Exception) {
            Logger.e(e) { "Suwayomi sync failed" }
            record(
                preferences,
                applicationContext.getString(
                    MR.strings.suwayomi_sync_failed_summary,
                    e.message ?: e::class.simpleName.orEmpty(),
                ),
            )
            Result.retry()
        }
    }

    /** The sync does not stop on a question nobody can answer in the background: it leaves the removals and says so. */
    private fun notifyRemovalsWaiting(count: Int) {
        val open = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_COMMON) {
            setSmallIcon(android.R.drawable.stat_notify_sync)
            setContentTitle(applicationContext.getString(MR.strings.suwayomi_removals_notification_title))
            setContentText(applicationContext.getString(MR.strings.suwayomi_removals_notification_text, count))
            setContentIntent(open)
            setAutoCancel(true)
        }.build()
        applicationContext.notificationManager.notify(Notifications.ID_SYNC_REMOVALS, notification)
    }

    private fun record(preferences: ServerPreferences, summary: String) {
        preferences.lastSyncSummary().set(summary)
        preferences.lastSyncAt().set(System.currentTimeMillis())
    }

    private fun describe(result: SyncSuwayomiServer.Summary): String {
        val base = if (result.isIdle) {
            applicationContext.getString(MR.strings.suwayomi_sync_nothing_to_do)
        } else {
            applicationContext.getString(
                MR.strings.suwayomi_sync_counts,
                result.chaptersPulled + result.chaptersPushed,
                result.categoryLinksLocal + result.categoryLinksRemote +
                    result.categoriesCreatedLocal + result.categoriesCreatedRemote,
                result.mergedPulled + result.mergedPushed,
                result.deletionsApplied,
            )
        }
        val held = if (result.heldRemovals.isEmpty()) {
            base
        } else {
            base + " · " + applicationContext.getString(MR.strings.suwayomi_sync_removals_held, result.heldRemovals.size)
        }
        if (result.errors.isEmpty()) return held
        return held + " · " + applicationContext.getString(
            MR.strings.suwayomi_sync_partial,
            result.errors.joinToString("; "),
        )
    }

    companion object {
        fun isRunning(context: Context): Boolean = context.workManager
            .getWorkInfosByTag(TAG_MANUAL).get()
            .any { it.state == WorkInfo.State.RUNNING }

        /** Registers (or cancels) the periodic run from the current settings. */
        fun setupTask(context: Context, prefInterval: Int? = null) {
            val preferences = Injekt.get<ServerPreferences>()
            val interval = prefInterval ?: preferences.syncInterval().get()
            if (interval <= 0 || !isUsable(preferences)) {
                context.workManager.cancelUniqueWork(TAG_AUTO)
                return
            }

            val request = PeriodicWorkRequestBuilder<ServerSyncJob>(
                interval.toLong(),
                TimeUnit.HOURS,
                10,
                TimeUnit.MINUTES,
            )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .addTag(TAG_AUTO)
                .setConstraints(constraints(preferences))
                .build()

            context.workManager.enqueueUniquePeriodicWork(
                TAG_AUTO,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /**
         * Sync because something just happened that the server's copy depends on: the app opened
         * or a chapter was read. Same unique work as the "Sync now" button, so a burst of these is
         * one run. Silent when sync is off or the interval is Manual — none of these events is a
         * request to be synced. [delaySeconds] gives a write that is still in flight (the reader's
         * last page) time to land before the run reads it.
         */
        fun startIfAutomatic(context: Context, delaySeconds: Long = 0) {
            val preferences = Injekt.get<ServerPreferences>()
            if (preferences.syncInterval().get() <= 0) return
            startNow(context, delaySeconds)
        }

        /** Manual sync ignores the interval but still respects "only over Wi-Fi" and the master switch. */
        fun startNow(context: Context, delaySeconds: Long = 0) {
            val preferences = Injekt.get<ServerPreferences>()
            if (!isUsable(preferences)) return
            val request = OneTimeWorkRequestBuilder<ServerSyncJob>()
                .addTag(TAG_MANUAL)
                .setConstraints(constraints(preferences))
                .apply { if (delaySeconds > 0) setInitialDelay(delaySeconds, TimeUnit.SECONDS) }
                .build()
            context.workManager.enqueueUniqueWork(TAG_MANUAL, ExistingWorkPolicy.KEEP, request)
        }

        fun isUsable(preferences: ServerPreferences) =
            preferences.enabled().get() && preferences.primaryUrl().get().isNotBlank()

        private fun constraints(preferences: ServerPreferences) = Constraints(
            requiredNetworkType = if (preferences.onlyOverWifi().get()) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            },
        )

        private const val TAG_AUTO = "SuwayomiSync"
        private const val TAG_MANUAL = "$TAG_AUTO:manual"
    }
}
