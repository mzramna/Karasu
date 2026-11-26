package eu.kanade.tachiyomi.appwidget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import co.touchlab.kermit.Logger

class TachiyomiWidgetManager {

    // shortcut: widget refresh errors are only logged, so the widget can stay stale, upgrade if the system stops refusing the call (AppWidgetService SecurityException)
    suspend fun Context.init() {
        try {
            val manager = GlanceAppWidgetManager(this)
            if (manager.getGlanceIds(UpdatesGridGlanceWidget::class.java).isNotEmpty()) {
                UpdatesGridGlanceWidget().loadData()
            }
            refreshReleases()
        } catch (e: SecurityException) {
            Logger.w(e) { "Widget refresh refused by the system" }
        }
    }

    /**
     * Redraws the release widget if one is on the home screen.
     *
     * Called after a library update as well as at startup: an update is the only thing that
     * moves a release estimate, and a calendar widget showing yesterday's guess is worse than
     * one that is a few seconds late.
     */
    suspend fun Context.refreshReleases() {
        try {
            val manager = GlanceAppWidgetManager(this)
            if (manager.getGlanceIds(ReleasesGlanceWidget::class.java).isNotEmpty()) {
                ReleasesGlanceWidget().loadData()
            }
        } catch (e: SecurityException) {
            Logger.w(e) { "Releases widget refresh refused by the system" }
        }
    }
}
