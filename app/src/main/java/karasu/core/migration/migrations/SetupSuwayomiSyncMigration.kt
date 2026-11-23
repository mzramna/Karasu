package karasu.core.migration.migrations

import android.app.Application
import eu.kanade.tachiyomi.data.suwayomi.ServerSyncJob
import karasu.core.migration.Migration
import karasu.core.migration.MigrationContext

/**
 * Re-registers the Suwayomi sync on every start, cancels it when sync is switched off, and — when
 * an interval is set — syncs once now, so opening the app picks up what changed elsewhere.
 */
class SetupSuwayomiSyncMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val context = migrationContext.get<Application>() ?: return false
        ServerSyncJob.setupTask(context)
        ServerSyncJob.startIfAutomatic(context)
        return true
    }
}
