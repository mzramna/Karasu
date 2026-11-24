package karasu.domain.suwayomi.interactor

import karasu.data.suwayomi.ServerSyncApi
import karasu.domain.extension.repo.interactor.CreateExtensionRepo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The extension repositories (what the app's "Extension repositories" screen and the server's `extensionStores`
 * hold), in both directions, on the same two sides as the other live scenarios: a [LiveDevice] with the real
 * [SyncSuwayomiServer] and the server as another client (the WebUI) sees it. Mock server by default, a real one
 * with `KARASU_LIVE_URL`.
 *
 * Repositories come from [Live.repos] (the server fetches them from there when one is added), one per test, and the
 * server's copy is removed when the test ends, because a real server's state is shared and permanent.
 */
class SyncExtensionReposLiveTest {

    private val web: ServerSyncApi = Live.api()
    private val used = mutableListOf<String>()

    private var counter = 0
    private fun repo(): String = Live.repos.publish("repo-${System.nanoTime()}-${counter++}").also { used += it }

    private fun index(base: String) = "$base/repo.json"

    private fun serverRepos(): Set<String> =
        runBlocking { web.extensionStores() }.mapTo(mutableSetOf()) { it.removeSuffix("/repo.json") }

    private fun serverAdd(base: String) = runBlocking { web.addExtensionStore(index(base)) }

    private fun serverRemove(base: String) = runBlocking { web.removeExtensionStore(index(base)) }

    /** A moment later, so what comes next is newer than what came before on any clock. */
    private fun later() = Thread.sleep(60)

    private fun device(label: String) = LiveDevice(label).also { it.sync() }

    @AfterEach
    fun cleanUp() {
        used.forEach { runCatching { serverRemove(it) } }
    }

    @Test
    fun `a repository added on the app shows up on the server`() {
        val app = device("phone")
        val base = repo()

        assertEquals(CreateExtensionRepo.Result.Success, app.addRepo(base))
        val summary = app.sync()

        assertTrue(base in serverRepos(), "on the server")
        assertEquals(1, summary.reposPushed)
        assertTrue(summary.errors.isEmpty(), "no errors: ${summary.errors}")
    }

    @Test
    fun `a repository added on the server shows up on the app`() {
        val app = device("phone")
        val base = repo()

        serverAdd(base)
        val summary = app.sync()

        assertTrue(base in app.repoBases(), "on the app")
        assertEquals(1, summary.reposPulled)
        assertTrue(summary.errors.isEmpty(), "no errors: ${summary.errors}")
    }

    @Test
    fun `the first sync joins what each side already had and removes nothing`() {
        val app = LiveDevice("fresh")
        val mine = repo()
        val theirs = repo()
        app.addRepo(mine)
        serverAdd(theirs)

        app.sync()

        assertTrue(mine in serverRepos() && theirs in serverRepos(), "the server has both")
        assertTrue(mine in app.repoBases() && theirs in app.repoBases(), "the app has both")
    }

    @Test
    fun `removing a repository on the app removes it from the server`() {
        val app = device("phone")
        val base = repo()
        app.addRepo(base)
        app.sync()
        assertTrue(base in serverRepos())

        later()
        app.removeRepo(base)
        val summary = app.sync()

        assertFalse(base in serverRepos(), "gone from the server")
        assertFalse(base in app.repoBases(), "and not brought back")
        assertEquals(1, summary.reposRemovedRemote)
    }

    @Test
    fun `removing a repository on the app leaves the server alone when deletions are not pushed`() {
        val app = device("phone")
        app.preferences.pushLocalDeletions().set(false)
        val base = repo()
        app.addRepo(base)
        app.sync()

        later()
        app.removeRepo(base)
        app.sync()

        assertTrue(base in serverRepos(), "still on the server")
        assertFalse(base in app.repoBases(), "the app does not take it back on its own")
    }

    @Test
    fun `removing a repository on the server removes it from the app`() {
        val app = device("phone")
        val base = repo()
        serverAdd(base)
        app.sync()
        assertTrue(base in app.repoBases())

        later()
        serverRemove(base)
        val summary = app.sync()

        assertFalse(base in app.repoBases(), "gone from the app")
        assertFalse(base in serverRepos(), "and not brought back")
        assertEquals(1, summary.reposRemovedLocal)
    }

    @Test
    fun `removing a repository on the server keeps it on the app when remote deletions are not applied`() {
        val app = device("phone")
        app.preferences.applyRemoteDeletions().set(false)
        val base = repo()
        serverAdd(base)
        app.sync()

        later()
        serverRemove(base)
        app.sync()

        assertTrue(base in app.repoBases(), "the app never deletes on its own")
    }

    @Test
    fun `a repository removed on the app and added again on the server later comes back to the app`() {
        val app = device("phone")
        val base = repo()
        app.addRepo(base)
        app.sync()

        later()
        app.removeRepo(base)
        later()
        // the server's side did not know yet and another client puts it in again, after the removal
        runBlocking { web.removeExtensionStore(index(base)) }
        later()
        serverAdd(base)
        app.sync()

        assertTrue(base in app.repoBases(), "the newer action, adding, wins")
        assertTrue(base in serverRepos())
    }

    @Test
    fun `a repository removed on the server and added again on the app later goes back to the server`() {
        val app = device("phone")
        val base = repo()
        serverAdd(base)
        app.sync()

        later()
        serverRemove(base)
        later()
        app.removeRepo(base)
        later()
        app.addRepo(base)
        app.sync()

        assertTrue(base in serverRepos(), "the newer action, adding, wins")
        assertTrue(base in app.repoBases())
    }

    @Test
    fun `two devices follow each other through the server, adding and removing`() {
        val phone = device("phone")
        val tablet = device("tablet")
        val base = repo()

        phone.addRepo(base)
        phone.sync()
        tablet.sync()
        assertTrue(base in tablet.repoBases(), "added on the phone, on the tablet")

        later()
        tablet.removeRepo(base)
        tablet.sync()
        phone.sync()
        assertFalse(base in serverRepos(), "removed on the tablet, off the server")
        assertFalse(base in phone.repoBases(), "and off the phone")
    }

    @Test
    fun `a repository the server cannot fetch stays on the app and does not stop the rest of the sync`() {
        val app = device("phone")
        val gone = repo()
        val keep = repo()
        app.addRepo(gone)
        app.addRepo(keep)
        Live.repos.unpublish(gone.substringAfterLast('/')) // its owner took it down

        val category = "after-bad-repo-${System.nanoTime()}"
        app.createCategory(category)
        val summary = app.sync()

        assertTrue(gone in app.repoBases(), "kept on the app")
        assertFalse(gone in serverRepos(), "the server could not take it")
        assertTrue(category in runBlocking { web.categories() }.map { it.name }, "the categories still synced")
        assertTrue(keep in serverRepos() || summary.errors.isNotEmpty(), "reported, not silent: ${summary.errors}")
    }

    @Test
    fun `with nothing changed a second sync does nothing`() {
        val app = device("phone")
        val base = repo()
        app.addRepo(base)
        app.sync()

        val quiet = app.sync()

        assertEquals(0, quiet.reposPushed + quiet.reposPulled + quiet.reposRemovedLocal + quiet.reposRemovedRemote)
    }
}
