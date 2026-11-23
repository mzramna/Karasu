package karasu.presentation.settings.screen.data

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hippo.unifile.UniFile
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.data.backup.BackupFileValidator.Results
import eu.kanade.tachiyomi.data.backup.create.BackupCreatorJob
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.util.system.toast
import karasu.data.suwayomi.ServerBackupTransfer
import karasu.data.suwayomi.ServerSyncApi
import karasu.domain.DialogHostState
import karasu.domain.suwayomi.ServerPreferences
import karasu.domain.suwayomi.models.BackupInfo
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import karasu.i18n.MR
import karasu.presentation.component.LabeledCheckbox
import karasu.util.lang.getString
import android.R as AR

suspend fun DialogHostState.awaitRestoreBackup(
    context: Context,
    uri: Uri,
    pair: Pair<Results?, Exception?>,
): Unit = dialog { cont ->
    val (results, e) = pair
    if (results != null) {
        var message = stringResource(MR.strings.restore_content_full)
        if (results.missingSources.isNotEmpty()) {
            message += "\n\n${stringResource(MR.strings.restore_missing_sources)}\n${
                results.missingSources.joinToString(
                    "\n",
                ) { "- $it" }
            }"
        }
        if (results.missingTrackers.isNotEmpty()) {
            message += "\n\n${stringResource(MR.strings.restore_missing_trackers)}\n${
                results.missingTrackers.joinToString(
                    "\n",
                ) { "- $it" }
            }"
        }

        AlertDialog(
            onDismissRequest = { cont.cancel() },
            confirmButton = {
                TextButton(
                    onClick = {
                        context.toast(MR.strings.restoring_backup)
                        BackupRestoreJob.start(context, uri)
                        cont.cancel()
                    },
                ) {
                    Text(text = stringResource(MR.strings.restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { cont.cancel() }) {
                    Text(text = stringResource(AR.string.cancel))
                }
            },
            title = { Text(text = stringResource(MR.strings.restore_backup)) },
            text = { Text(text = message) },
        )
    } else {
        AlertDialog(
            onDismissRequest = { cont.cancel() },
            confirmButton = {
                TextButton(onClick = { cont.cancel() }) {
                    Text(text = stringResource(AR.string.cancel))
                }
            },
            title = { Text(text = stringResource(MR.strings.invalid_backup_file)) },
            text = { e?.message?.let { Text(text = it) } }
        )
    }
}

suspend fun DialogHostState.awaitCreateBackup(
    context: Context,
    uri: Uri,
): Unit = dialog { cont ->
    var options by mutableStateOf(BackupOptions())
    // Only offered when sync with the server is on, so a person who never set it up never sees it.
    val canUpload = remember { Injekt.get<ServerSyncApi>().isEnabled }
    var upload by mutableStateOf(canUpload && Injekt.get<ServerPreferences>().uploadManualBackups().get())

    AlertDialog(
        onDismissRequest = { cont.cancel() },
        confirmButton = {
            TextButton(onClick = {
                val actualUri =
                    UniFile.fromUri(context, uri)?.createFile(Backup.getBackupFilename())?.uri ?: return@TextButton
                context.toast(MR.strings.creating_backup)
                BackupCreatorJob.startNow(context, actualUri, options, uploadToServer = canUpload && upload)
                cont.cancel()
            }) {
                Text(stringResource(MR.strings.create))
            }
        },
        dismissButton = {
            TextButton(onClick = { cont.cancel() }) {
                Text(stringResource(MR.strings.cancel))
            }
        },
        title = { Text(text = stringResource(MR.strings.create_backup)) },
        text = {
            Box {
                val state = rememberLazyListState()
                LazyColumn(state = state) {
                    BackupOptions.getEntries().forEach { option ->
                        item {
                            LabeledCheckbox(
                                label = stringResource(option.label),
                                checked = option.getter(options),
                                onCheckedChange = { options = option.setter(options, it) },
                                enabled = option.enabled(options),
                            )
                        }
                    }
                    if (canUpload) {
                        item {
                            LabeledCheckbox(
                                label = stringResource(MR.strings.suwayomi_backup_also_upload),
                                checked = upload,
                                onCheckedChange = { upload = it },
                            )
                        }
                    }
                }
            }
        },
    )
}

/**
 * Lists the backups stored on the Suwayomi server, downloads the chosen one and hands it to the
 * regular restore confirmation — from there it is an ordinary restore from a file.
 */
suspend fun DialogHostState.awaitRestoreFromServer(context: Context) {
    val api = Injekt.get<ServerSyncApi>()
    val picked = dialog<BackupInfo?> { cont ->
        ServerBackupPicker(
            context = context,
            api = api,
            onPick = { cont.resume(it) },
            onDismiss = { cont.resume(null) },
        )
    } ?: return

    context.toast(MR.strings.suwayomi_restore_downloading)
    val uri = try {
        withContext(Dispatchers.IO) { Injekt.get<ServerBackupTransfer>().downloadForRestore(picked) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        context.toast(context.getString(MR.strings.suwayomi_restore_failed, e.message ?: e::class.simpleName.orEmpty()))
        return
    }
    val results = try {
        Pair(BackupFileValidator().validate(context, uri), null)
    } catch (e: Exception) {
        Pair(null, e)
    }
    awaitRestoreBackup(context, uri, results)
}

private sealed interface ServerBackups {
    data object Loading : ServerBackups
    data class Loaded(val items: List<BackupInfo>) : ServerBackups
    data class Failed(val message: String) : ServerBackups
}

@Composable
private fun ServerBackupPicker(
    context: Context,
    api: ServerSyncApi,
    onPick: (BackupInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    var state by remember { mutableStateOf<ServerBackups>(ServerBackups.Loading) }
    LaunchedEffect(Unit) {
        state = try {
            ServerBackups.Loaded(withContext(Dispatchers.IO) { api.listBackups() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ServerBackups.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(AR.string.cancel)) }
        },
        title = { Text(text = stringResource(MR.strings.suwayomi_restore_choose_title)) },
        text = {
            when (val current = state) {
                ServerBackups.Loading -> Text(text = stringResource(MR.strings.suwayomi_restore_loading))
                is ServerBackups.Failed ->
                    Text(text = context.getString(MR.strings.suwayomi_restore_failed, current.message))
                is ServerBackups.Loaded -> if (current.items.isEmpty()) {
                    Text(text = stringResource(MR.strings.suwayomi_restore_none))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(current.items, key = { it.id }) { backup ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(backup) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(text = backup.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = describe(context, backup),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

private fun describe(context: Context, backup: BackupInfo): String {
    val age = DateUtils.getRelativeTimeSpanString(backup.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    val size = Formatter.formatShortFileSize(context, backup.sizeBytes)
    val device = backup.device?.takeIf { it.isNotBlank() }
    return if (device == null) {
        context.getString(MR.strings.suwayomi_backup_item, age, size)
    } else {
        context.getString(MR.strings.suwayomi_backup_item_device, device, age, size)
    }
}
