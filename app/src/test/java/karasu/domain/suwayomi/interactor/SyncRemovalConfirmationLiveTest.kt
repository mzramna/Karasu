package karasu.domain.suwayomi.interactor

import karasu.data.suwayomi.ServerSyncApi
import karasu.domain.suwayomi.RemovalConfirmation
import karasu.domain.suwayomi.models.RemovalKind
import karasu.domain.suwayomi.models.RemovalSide
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A sync that would remove something, on either side, does not do it until the user says so: what it found is listed and
 * left as it was, and the approval lets exactly that list through. Mock server by default, a real one with
 * `KARASU_LIVE_URL`; the repositories come from [Live.repos] and the server's copy is removed when the test ends.
 */
class SyncRemovalConfirmationLiveTest {

    private val web: ServerSyncApi = Live.api()
    private val used = mutableListOf<String>()
    private var counter = 0

    private fun repo(): String = Live.repos.publish("removal-${System.nanoTime()}-${counter++}").also { used += it }

    private fun serverRepos(): Set<String> = runBlocking { web.extensionStores() }.mapTo(mutableSetOf()) { it.removeSuffix("/repo.json") }

    private fun later() = Thread.sleep(250)

    private fun remoteMangaId(n: Int): Int {
        var id = -1
        runBlocking { web.forEachLibraryPage { page -> page.firstOrNull { it.url == Live.mangaUrl(n) }?.let { id = it.id } } }
        check(id >= 0) { "work $n is not in the server's library" }
        return id
    }

    private fun attached(label: String) = LiveDevice(label).also { it.sync() }

    @AfterEach
    fun cleanUp() {
        used.forEach { runCatching { runBlocking { web.removeExtensionStore("$it/repo.json") } } }
    }

    @Test
    fun `a repository removed here stays on the server until the removal is approved`() {
        val app = attached("phone")
        val base = repo()
        app.addRepo(base)
        app.sync()
        later()
        app.removeRepo(base)

        val held = app.sync(approved = emptySet())

        assertTrue(base in serverRepos(), "not removed on the server")
        val removal = held.heldRemovals.single()
        assertEquals(RemovalKind.EXTENSION_REPO, removal.kind)
        assertEquals(RemovalSide.SERVER, removal.side)
        assertEquals("repo-server:$base", removal.key)

        app.sync(approved = setOf(removal.key))

        assertFalse(base in serverRepos(), "removed once approved")
    }

    @Test
    fun `a repository removed on the server stays on this device until the removal is approved`() {
        val app = attached("phone")
        val base = repo()
        runBlocking { web.addExtensionStore("$base/repo.json") }
        app.sync()
        assertTrue(base in app.repoBases())
        later()
        runBlocking { web.removeExtensionStore("$base/repo.json") }

        val held = app.sync(approved = emptySet())

        assertTrue(base in app.repoBases(), "not removed here")
        val removal = held.heldRemovals.single { it.key == "repo-here:$base" }
        assertEquals(RemovalSide.THIS_DEVICE, removal.side)

        app.sync(approved = setOf(removal.key))

        assertFalse(base in app.repoBases(), "removed once approved")
    }

    @Test
    fun `a work taken out of a category on the server stays in it here until the removal is approved`() {
        val app = LiveDevice("phone").also { it.addManga(40) }
        val name = "keep-${System.nanoTime()}"
        app.createCategory(name)
        app.setCategories(40, name)
        app.sync()
        val id = runBlocking { web.categories() }.first { it.name == name }.id
        val remoteId = remoteMangaId(40)
        runBlocking { web.updateMangaCategories(remoteId, emptyList(), listOf(id)) } // the web takes it out

        val held = app.sync(approved = emptySet())

        assertEquals(setOf(name), app.categoriesOf(40), "still in it here")
        val removal = held.heldRemovals.single { it.kind == RemovalKind.CATEGORY_LINK && it.key.endsWith("|${name.lowercase()}") }
        assertEquals(RemovalSide.THIS_DEVICE, removal.side)

        app.sync(approved = setOf(removal.key))

        assertEquals(emptySet<String>(), app.categoriesOf(40), "out of it once approved")
    }

    @Test
    fun `an approval covers the removals the user saw and not one that came after`() {
        val app = attached("phone")
        val first = repo()
        val second = repo()
        app.addRepo(first)
        app.addRepo(second)
        app.sync()
        later()
        app.removeRepo(first)
        val seen = app.sync(approved = emptySet()).heldRemovals.single { it.key == "repo-server:$first" }
        app.removeRepo(second) // after the list was shown

        val after = app.sync(approved = setOf(seen.key))

        assertFalse(first in serverRepos(), "the approved one is removed")
        assertTrue(second in serverRepos(), "the new one is not")
        assertEquals("repo-server:$second", after.heldRemovals.single().key, "and it is asked about next")
    }

    @Test
    fun `run like the job, the question comes first and the answer lets the same removal through`() {
        val app = attached("phone")
        val base = repo()
        app.addRepo(base)
        app.syncLikeTheJob()
        later()
        app.removeRepo(base)
        val confirmation = RemovalConfirmation(app.preferences)

        app.syncLikeTheJob()

        assertTrue(base in serverRepos(), "waiting")
        assertTrue(confirmation.needsAnswer())
        assertEquals(listOf("repo-server:$base"), confirmation.pending().map { it.key })

        confirmation.approve()
        app.syncLikeTheJob()

        assertFalse(base in serverRepos(), "done")
        assertTrue(confirmation.pending().isEmpty())
        assertFalse(confirmation.needsAnswer())
    }

    @Test
    fun `with the question turned off the removal is done at once`() {
        val app = attached("phone")
        app.preferences.confirmRemovals().set(false)
        val base = repo()
        app.addRepo(base)
        app.syncLikeTheJob()
        later()
        app.removeRepo(base)

        val summary = app.syncLikeTheJob()

        assertFalse(base in serverRepos())
        assertTrue(summary.heldRemovals.isEmpty())
    }
}
