package karasu.domain.suwayomi

import karasu.domain.suwayomi.interactor.InMemoryPreferenceStore
import karasu.domain.suwayomi.interactor.SyncSuwayomiServer
import karasu.domain.suwayomi.models.PlannedRemoval
import karasu.domain.suwayomi.models.RemovalKind
import karasu.domain.suwayomi.models.RemovalSide
import karasu.domain.suwayomi.models.groupRemovals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemovalConfirmationTest {

    private val preferences = ServerPreferences(InMemoryPreferenceStore())
    private val confirmation = RemovalConfirmation(preferences)

    private fun removal(key: String, side: RemovalSide = RemovalSide.SERVER, kind: RemovalKind = RemovalKind.EXTENSION_REPO) =
        PlannedRemoval(kind, side, key, "label of $key")

    private fun held(vararg removals: PlannedRemoval) = SyncSuwayomiServer.Summary(heldRemovals = removals.toList())

    @Test
    fun `by default nothing is approved, so every removal is held`() {
        assertEquals(emptySet<String>(), confirmation.approvedForNextRun())
    }

    @Test
    fun `with the question turned off every removal goes through`() {
        preferences.confirmRemovals().set(false)

        assertNull(confirmation.approvedForNextRun())
    }

    @Test
    fun `what a sync holds becomes the pending list and is asked about`() {
        confirmation.recordRun(held(removal("a"), removal("b")))

        assertEquals(listOf("a", "b"), confirmation.pending().map { it.key })
        assertTrue(confirmation.needsAnswer())
    }

    @Test
    fun `approving lets exactly the pending removals through on the next run and is spent after it`() {
        confirmation.recordRun(held(removal("a"), removal("b")))

        confirmation.approve()

        assertEquals(setOf("a", "b"), confirmation.approvedForNextRun())
        confirmation.recordRun(SyncSuwayomiServer.Summary()) // the run that did them
        assertEquals(emptySet<String>(), confirmation.approvedForNextRun(), "the approval does not outlive its run")
        assertTrue(confirmation.pending().isEmpty())
    }

    @Test
    fun `an approval does not cover a removal that was not on the list the user saw`() {
        confirmation.recordRun(held(removal("a")))
        confirmation.approve()

        val approved = confirmation.approvedForNextRun()!!

        assertTrue("a" in approved)
        assertFalse("b" in approved)
    }

    @Test
    fun `not now is remembered for that list only`() {
        confirmation.recordRun(held(removal("a")))

        confirmation.decline()

        assertFalse(confirmation.needsAnswer(), "the same list is not asked again")
        assertEquals(listOf("a"), confirmation.pending().map { it.key }, "but it is still waiting")

        confirmation.recordRun(held(removal("a"), removal("c")))

        assertTrue(confirmation.needsAnswer(), "a different list is a new question")
    }

    @Test
    fun `when a run holds nothing the pending list and the declined mark are cleared`() {
        confirmation.recordRun(held(removal("a")))
        confirmation.decline()

        confirmation.recordRun(SyncSuwayomiServer.Summary())

        assertTrue(confirmation.pending().isEmpty())
        confirmation.recordRun(held(removal("a")))
        assertTrue(confirmation.needsAnswer(), "the same removal showing up later is asked about again")
    }

    @Test
    fun `unreadable stored values approve and list nothing`() {
        preferences.approvedRemovals().set("{not json")
        preferences.pendingRemovals().set("{not json")

        assertEquals(emptySet<String>(), confirmation.approvedForNextRun())
        assertTrue(confirmation.pending().isEmpty())
        assertFalse(confirmation.needsAnswer())
    }

    @Test
    fun `removals are grouped by side and kind with a few examples each`() {
        val list = (1..5).map { removal("d$it", RemovalSide.THIS_DEVICE, RemovalKind.DOWNLOADED_CHAPTER) } +
            removal("r", RemovalSide.SERVER, RemovalKind.EXTENSION_REPO) +
            removal("h", RemovalSide.THIS_DEVICE, RemovalKind.HISTORY_ENTRY)

        val groups = groupRemovals(list, examples = 2)

        assertEquals(
            listOf(
                RemovalSide.THIS_DEVICE to RemovalKind.DOWNLOADED_CHAPTER,
                RemovalSide.THIS_DEVICE to RemovalKind.HISTORY_ENTRY,
                RemovalSide.SERVER to RemovalKind.EXTENSION_REPO,
            ),
            groups.map { it.side to it.kind },
        )
        assertEquals(5, groups.first().count)
        assertEquals(listOf("label of d1", "label of d2"), groups.first().examples)
    }
}
