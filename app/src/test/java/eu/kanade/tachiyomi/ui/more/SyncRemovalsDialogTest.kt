package eu.kanade.tachiyomi.ui.more

import android.app.Activity
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import eu.kanade.tachiyomi.data.suwayomi.ServerSyncJob
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import karasu.domain.suwayomi.RemovalConfirmation
import karasu.domain.suwayomi.ServerPreferences
import karasu.domain.suwayomi.interactor.InMemoryPreferenceStore
import karasu.domain.suwayomi.interactor.SyncSuwayomiServer
import karasu.domain.suwayomi.models.PlannedRemoval
import karasu.domain.suwayomi.models.RemovalKind
import karasu.domain.suwayomi.models.RemovalSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** An activity with a Material theme and nothing else: the dialog needs a window to live in, not the app. */
class DialogHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(com.google.android.material.R.style.Theme_Material3_DayNight)
        super.onCreate(savedInstanceState)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncRemovalsDialogTest {

    private val preferences = ServerPreferences(InMemoryPreferenceStore())
    private val confirmation = RemovalConfirmation(preferences)

    private fun removal(kind: RemovalKind, side: RemovalSide, key: String, label: String) = PlannedRemoval(kind, side, key, label)

    private fun held() {
        confirmation.recordRun(
            SyncSuwayomiServer.Summary(
                heldRemovals = listOf(
                    removal(RemovalKind.DOWNLOADED_CHAPTER, RemovalSide.THIS_DEVICE, "d1", "One Piece - Chapter 1100"),
                    removal(RemovalKind.DOWNLOADED_CHAPTER, RemovalSide.THIS_DEVICE, "d2", "One Piece - Chapter 1101"),
                    removal(RemovalKind.EXTENSION_REPO, RemovalSide.SERVER, "r1", "https://example.test/repo"),
                ),
            ),
        )
    }

    private fun open(): AlertDialog {
        val activity = Robolectric.buildActivity(DialogHostActivity::class.java).setup().get()
        return SyncRemovalsDialog.show(activity, confirmation)!!
    }

    @Test
    fun `the dialog lists what would be removed, by side and kind, and says nothing was removed yet`() {
        held()

        val dialog = open()

        assertTrue(dialog.isShowing)
        val text = dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()
        println("DIALOG TEXT >>>\n$text\n<<<")
        screenshot(dialog, "sync-removals-dialog")
        assertTrue(text, text.contains("3"))
        assertTrue(text, text.contains("One Piece - Chapter 1100"))
        assertTrue(text, text.contains("https://example.test/repo"))
    }

    /** Draws the dialog's window into a PNG when ROBOLECTRIC_SCREENSHOTS names a directory (the native graphics mode renders it). */
    private fun screenshot(dialog: AlertDialog, name: String) {
        val dir = System.getenv("ROBOLECTRIC_SCREENSHOTS") ?: return
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val view = dialog.window!!.decorView
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bitmap = android.graphics.Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        java.io.File(dir).mkdirs()
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(qualifiers = "pt-rBR")
    fun `the dialog is in Portuguese on a Portuguese device`() {
        held()

        val dialog = open()

        val text = dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()
        assertTrue(text, text.contains("Nada foi removido ainda"))
        assertTrue(text, text.contains("Capítulos baixados (2)"))
        assertEquals("Remover e sincronizar", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("Agora não", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        screenshot(dialog, "sync-removals-dialog-pt-br")
    }

    @Test
    fun `remove and sync approves exactly the listed removals and starts a sync`() {
        held()
        mockkObject(ServerSyncJob.Companion)
        try {
            every { ServerSyncJob.startNow(any(), any()) } just Runs
            val dialog = open()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle() // a dialog button reports its click through the main looper

            assertEquals(setOf("d1", "d2", "r1"), confirmation.approvedForNextRun())
            verify(exactly = 1) { ServerSyncJob.startNow(any(), any()) }
        } finally {
            unmockkObject(ServerSyncJob.Companion)
        }
    }

    @Test
    fun `not now removes nothing, starts nothing and is not asked again for the same list`() {
        held()
        mockkObject(ServerSyncJob.Companion)
        try {
            every { ServerSyncJob.startNow(any(), any()) } just Runs
            val dialog = open()

            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()

            assertEquals(emptySet<String>(), confirmation.approvedForNextRun())
            assertFalse(confirmation.needsAnswer())
            verify(exactly = 0) { ServerSyncJob.startNow(any(), any()) }
        } finally {
            unmockkObject(ServerSyncJob.Companion)
        }
    }

    @Test
    fun `nothing is shown when nothing is pending`() {
        val activity = Robolectric.buildActivity(DialogHostActivity::class.java).setup().get()

        assertEquals(null, SyncRemovalsDialog.show(activity, confirmation))
        assertNotNull(activity)
    }
}
