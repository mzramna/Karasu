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
}
