package karasu.data.suwayomi

import android.content.Context
import android.net.Uri
import android.os.Build
import com.hippo.unifile.UniFile
import java.io.File
import java.io.IOException
import karasu.domain.suwayomi.models.BackupInfo

/**
 * Moves backup files between the device and the server's backup store.
 *
 * Uploads stream from whatever the backup was written to (a SAF document or a plain file), and
 * downloads land in the app's cache, which is where the restore job then reads them from — the
 * restore code only knows how to read a `Uri`, so this hands it one.
 */
class ServerBackupTransfer(
    private val context: Context,
    private val api: ServerSyncApi,
) {

    /** Sends the backup at [uri] to the server, labelled with this device's name. */
    suspend fun upload(uri: Uri): BackupInfo {
        val file = UniFile.fromUri(context, uri) ?: throw IOException("Backup file not found")
        val name = file.name?.takeIf { it.isNotBlank() } ?: "karasu.tachibk"
        return api.uploadBackup(
            fileName = name,
            device = deviceName(),
            length = file.length().takeIf { it > 0 } ?: -1L,
        ) {
            context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read the backup file")
        }
    }

    /** Downloads [backup] to the cache and returns the file's `Uri`, ready for the restore job. */
    suspend fun downloadForRestore(backup: BackupInfo): Uri {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        // Only the last component of the name is used: the id comes from the network.
        val safeName = backup.name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "backup.tachibk" }
        // One restore at a time is enough; clearing the rest keeps large files from piling up.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, safeName)
        api.downloadBackup(backup.id, target)
        return Uri.fromFile(target)
    }

    private fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android" }

    private companion object {
        const val CACHE_DIR = "suwayomi_backups"
    }
}
