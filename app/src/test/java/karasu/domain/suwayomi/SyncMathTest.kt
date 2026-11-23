package karasu.domain.suwayomi

import karasu.domain.suwayomi.SyncMath.MergedEntry
import karasu.domain.suwayomi.SyncMath.Progress
import karasu.domain.suwayomi.SyncMath.toEntries
import karasu.domain.suwayomi.models.MergedSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncMathTest {

    @Test
    fun `first sync is a plain union`() {
        assertEquals(setOf("a", "b", "c"), SyncMath.mergeSets(emptySet(), setOf("a", "b"), setOf("b", "c")))
    }

    @Test
    fun `a removal on one side survives the next sync`() {
        // "b" was agreed on, then removed locally; the server still has it.
        val result = SyncMath.mergeSets(setOf("a", "b"), setOf("a"), setOf("a", "b"))
        assertEquals(setOf("a"), result)
    }

    @Test
    fun `an addition on one side is kept while the other side removes something else`() {
        val result = SyncMath.mergeSets(setOf("a", "b"), setOf("a", "b", "x"), setOf("a"))
        assertEquals(setOf("a", "x"), result)
    }

    @Test
    fun `reading only moves forward`() {
        assertEquals(Progress(true, 9), SyncMath.mergeProgress(Progress(false, 9), Progress(true, 2)))
        assertEquals(Progress(false, 7), SyncMath.mergeProgress(Progress(false, 3), Progress(false, 7)))
    }

    private fun entry(id: String, enabled: Boolean = true) = MergedEntry(id, "/m/$id", enabled)

    @Test
    fun `identical merged lists change nothing`() {
        val outcome = SyncMath.mergeMerged(null, listOf(entry("1")), listOf(entry("1")))
        assertFalse(outcome.changeLocal)
        assertFalse(outcome.changeRemote)
    }

    @Test
    fun `a remote-only change is pulled when local is untouched`() {
        val base = listOf(entry("1"), entry("2"))
        val remote = listOf(entry("2"), entry("1")) // reordered on the server
        val outcome = SyncMath.mergeMerged(base, base, remote)
        assertEquals(remote, outcome.result)
        assertTrue(outcome.changeLocal)
        assertFalse(outcome.changeRemote)
    }

    @Test
    fun `a local-only change is pushed when the server is untouched`() {
        val base = listOf(entry("1"), entry("2"))
        val local = listOf(entry("1"))
        val outcome = SyncMath.mergeMerged(base, local, base)
        assertEquals(local, outcome.result)
        assertFalse(outcome.changeLocal)
        assertTrue(outcome.changeRemote)
    }

    @Test
    fun `both sides changed merges as a set with local order first`() {
        val base = listOf(entry("1"), entry("2"))
        val local = listOf(entry("2"), entry("1"), entry("3")) // added 3 and reordered
        val remote = listOf(entry("1"), entry("4")) // removed 2, added 4
        val outcome = SyncMath.mergeMerged(base, local, remote)
        // 2 was removed remotely, so it goes; 3 and 4 were added, so they stay.
        assertEquals(listOf("1", "3", "4"), outcome.result.map { it.sourceId })
        assertTrue(outcome.changeLocal)
        assertTrue(outcome.changeRemote)
    }

    @Test
    fun `without a base the two sides are unioned`() {
        val outcome = SyncMath.mergeMerged(null, listOf(entry("1")), listOf(entry("2")))
        assertEquals(listOf("1", "2"), outcome.result.map { it.sourceId })
    }

    @Test
    fun `entries are ordered by priority and the source id may arrive as a number`() {
        val entries = listOf(
            MergedSource("20", "/b", priority = 1),
            MergedSource("10", "/a", priority = 0, updatesEnabled = false),
        ).toEntries()
        assertEquals(listOf("10", "20"), entries.map { it.sourceId })
        assertFalse(entries.first().updatesEnabled)
    }
}
