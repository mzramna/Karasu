package eu.kanade.tachiyomi.ui.more

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import eu.kanade.tachiyomi.data.suwayomi.ServerSyncJob
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import karasu.domain.suwayomi.RemovalConfirmation
import karasu.domain.suwayomi.models.PlannedRemoval
import karasu.domain.suwayomi.models.RemovalKind
import karasu.domain.suwayomi.models.RemovalSide
import karasu.domain.suwayomi.models.groupRemovals
import karasu.i18n.MR
import karasu.util.lang.getString

/**
 * Asks whether the removals a sync found should really be done. Nothing has been removed when this is shown: "Remove"
 * lets exactly the listed removals through on the next sync, "Not now" leaves them (and does not ask about the same list
 * again).
 */
object SyncRemovalsDialog {

    fun show(activity: Activity, confirmation: RemovalConfirmation): AlertDialog? {
        val pending = confirmation.pending()
        if (pending.isEmpty()) return null
        return activity.materialAlertDialog()
            .setTitle(activity.getString(MR.strings.suwayomi_removals_title))
            .setMessage(message(activity, pending))
            .setPositiveButton(activity.getString(MR.strings.suwayomi_removals_confirm)) { _, _ ->
                confirmation.approve()
                ServerSyncJob.startNow(activity.applicationContext)
            }
            .setNegativeButton(activity.getString(MR.strings.suwayomi_removals_decline)) { _, _ -> confirmation.decline() }
            .setOnCancelListener { confirmation.decline() }
            .show()
    }

    private fun message(activity: Activity, removals: List<PlannedRemoval>): String = buildString {
        appendLine(activity.getString(MR.strings.suwayomi_removals_message, removals.size))
        val groups = groupRemovals(removals)
        for (side in RemovalSide.values()) {
            val ofSide = groups.filter { it.side == side }
            if (ofSide.isEmpty()) continue
            appendLine()
            appendLine(
                activity.getString(
                    if (side == RemovalSide.THIS_DEVICE) MR.strings.suwayomi_removals_here else MR.strings.suwayomi_removals_server,
                ),
            )
            for (group in ofSide) {
                append("• ").append(activity.getString(kindName(group.kind), group.count))
                append(": ").append(group.examples.joinToString("; "))
                if (group.count > group.examples.size) {
                    append(" ").append(activity.getString(MR.strings.suwayomi_removals_more, group.count - group.examples.size))
                }
                appendLine()
            }
        }
    }.trimEnd()

    private fun kindName(kind: RemovalKind) = when (kind) {
        RemovalKind.DOWNLOADED_CHAPTER -> MR.strings.suwayomi_removal_kind_downloaded
        RemovalKind.LIBRARY_WORK -> MR.strings.suwayomi_removal_kind_library
        RemovalKind.EXTENSION_REPO -> MR.strings.suwayomi_removal_kind_repo
        RemovalKind.MERGED_SOURCE -> MR.strings.suwayomi_removal_kind_merged
        RemovalKind.HISTORY_ENTRY -> MR.strings.suwayomi_removal_kind_history
        RemovalKind.CATEGORY_LINK -> MR.strings.suwayomi_removal_kind_category
    }
}
