package karasu.domain.operations

import karasu.domain.suwayomi.ServerPreferences
import karasu.domain.suwayomi.interactor.InMemoryPreferenceStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OperationLogTest {

    @Test
    fun `the target tells two extension repos apart`() = runBlocking {
        val log = OperationLog(ServerPreferences(InMemoryPreferenceStore())) { 100L }
        log.record(OpEntity.EXTENSION_REPO, OpAction.SET, name = "https://a/repo")
        log.record(OpEntity.EXTENSION_REPO, OpAction.SET, name = "https://b/repo")

        assertEquals(2, log.pending().size)
        assertEquals(100L, log.latest(OpEntity.EXTENSION_REPO, name = "https://a/repo"))
        assertNull(log.latest(OpEntity.EXTENSION_REPO, name = "https://c/repo"))
    }

    @Test
    fun `operations the server confirmed are forgotten and the others stay`() = runBlocking {
        val log = OperationLog(ServerPreferences(InMemoryPreferenceStore())) { 100L }
        log.record(OpEntity.EXTENSION_REPO, OpAction.SET, name = "https://a/repo")
        log.record(OpEntity.EXTENSION_REPO, OpAction.SET, name = "https://b/repo")
        val sent = log.pending().first { it.name == "https://a/repo" }

        log.remove(listOf(sent.id))

        assertEquals(listOf("https://b/repo"), log.all().map { it.name })
        assertNull(log.latest(OpEntity.EXTENSION_REPO, name = "https://a/repo"), "its record is the server's now")
    }

    @Test
    fun `the last operation on a repository says whether it was added or removed`() = runBlocking {
        var now = 100L
        val log = OperationLog(ServerPreferences(InMemoryPreferenceStore())) { now }
        log.record(OpEntity.EXTENSION_REPO, OpAction.SET, name = "https://a/repo")
        now = 200L
        log.record(OpEntity.EXTENSION_REPO, OpAction.REMOVE, name = "https://a/repo")

        val last = log.lastOp(OpEntity.EXTENSION_REPO, "https://a/repo")

        assertEquals(OpAction.REMOVE, last?.action)
        assertEquals(200L, last?.ts)
        assertNull(log.lastOp(OpEntity.EXTENSION_REPO, "https://other/repo"))
    }
}
