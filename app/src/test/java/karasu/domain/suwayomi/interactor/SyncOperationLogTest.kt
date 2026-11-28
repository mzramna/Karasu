package karasu.domain.suwayomi.interactor

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test

/**
 * The operation log as the thing the server and the devices keep each other honest with: a device acknowledges what
 * it applied, the server forgets what every device acknowledged, a device that was away too long reconciles by state.
 *
 * Most of these need a server of their own (its log is counted), so they run against a fresh mock; the two that only
 * look at what the app does with its own record also run against a real server.
 */
class SyncOperationLogTest {

    private val servers = mutableListOf<MockSuwayomiServer>()
    private val repoDirs = mutableListOf<String>()

    private fun freshServer(version: Int = 3): MockSuwayomiServer =
        MockSuwayomiServer(works = 3).also { it.opsVersion = version; it.start(); servers += it }

    private fun deviceOn(server: MockSuwayomiServer, label: String): LiveDevice {
        val id = "log-$label-${System.nanoTime()}"
        return LiveDevice(label, id, Live.api(primary = server.url, deviceId = id))
    }

    private fun repo(): String = Live.repos.publish("log-${System.nanoTime()}-${repoDirs.size}").also { repoDirs += it }

    private fun later() = Thread.sleep(250)

    @AfterEach
    fun stop() = servers.forEach { it.stop() }

    @Test
    fun `what the app published is forgotten on the device once the server has it`() {
        val app = LiveDevice("phone").also { it.sync() }
        val base = Live.repos.publish("forget-${System.nanoTime()}")
        app.addRepo(base)
        assertEquals(1, app.operations.pending().size)

        app.sync()

        assertTrue(app.operations.all().isEmpty(), "the server's log is the record now: ${app.operations.all()}")
        runBlocking { Live.api().removeExtensionStore("$base/repo.json") } // the server is shared: leave it as found
    }

    @Test
    fun `the app acknowledges, in the next run, what it applied in the previous one`() {
        assumeFalse(Live.isReal)
        val server = freshServer()
        val app = deviceOn(server, "phone")
        app.addRepo(repo())

        app.sync()
        app.sync()
        app.sync()

        assertEquals(1L, server.ackRequests.last(), "it applied operation 1 in the second run and says so in the third")
        assertTrue(server.ackRequests.first() == null, "nothing to acknowledge in the first run")
    }

    @Test
    fun `when every device has acknowledged, the server's log is empty`() {
        assumeFalse(Live.isReal)
        val server = freshServer()
        val phone = deviceOn(server, "phone")
        val tablet = deviceOn(server, "tablet")
        phone.addRepo(repo())
        tablet.addRepo(repo())

        repeat(3) {
            phone.sync()
            tablet.sync()
        }

        assertEquals(emptyList<Any>(), server.operationLog(), "nothing left to say")
        assertTrue(phone.operations.all().isEmpty() && tablet.operations.all().isEmpty(), "and nothing waiting on the devices")
    }

    @Test
    fun `a device that was away too long reconciles by state and nothing is removed`() {
        assumeFalse(Live.isReal)
        val server = freshServer()
        val phone = deviceOn(server, "phone")
        val kept = repo()
        phone.addRepo(kept)
        phone.sync()
        phone.sync()
        val web = Live.api(primary = server.url)
        runBlocking { web.removeExtensionStore("$kept/repo.json") } // someone removed it while the phone was away

        server.resetNext = true // and the server has since forgotten that it did
        val summary = phone.sync()

        assertTrue(summary.logReset, "reported")
        assertTrue(kept in phone.repoBases(), "the phone keeps its repository")
        assertTrue(runBlocking { web.extensionStores() }.any { it.startsWith(kept) }, "and the server gets it back: by state, the union")
    }

    @Test
    fun `a removal made on the server while the device was attached reaches it even without the old snapshot`() {
        val phone = LiveDevice("phone").also { it.sync() }
        val base = Live.repos.publish("by-log-${System.nanoTime()}")
        val web = Live.api()
        runBlocking { web.addExtensionStore("$base/repo.json") }
        phone.sync()
        assertTrue(base in phone.repoBases())

        phone.preferences.extensionRepoSnapshot().set("") // the proof the old rule needed is gone
        later()
        runBlocking { web.removeExtensionStore("$base/repo.json") }
        val summary = phone.sync()

        assertFalse(base in phone.repoBases(), "the log says it was removed after the device last looked")
        assertEquals(1, summary.reposRemovedLocal)
    }

    @Test
    fun `a device joining for the first time is never made to delete what it already has by old history`() {
        val base = Live.repos.publish("old-history-${System.nanoTime()}")
        val web = Live.api()
        runBlocking {
            web.addExtensionStore("$base/repo.json")
            web.removeExtensionStore("$base/repo.json") // history: it was there once and was removed
        }
        val fresh = LiveDevice("new-install")
        fresh.addRepo(base) // the user has it here from before

        fresh.sync()

        assertTrue(base in fresh.repoBases(), "kept")
        assertTrue(runBlocking { web.extensionStores() }.any { it.startsWith(base) }, "and shared: a first run only joins")
        runBlocking { web.removeExtensionStore("$base/repo.json") }
    }

    @Test
    fun `the publish is compressed for a server that understands it and plain for one that does not`() {
        assumeFalse(Live.isReal)
        val modern = freshServer(version = 3)
        val old = freshServer(version = 2)
        deviceOn(modern, "new").also { it.addRepo(repo()); it.sync(); it.addRepo(repo()); it.sync() }
        deviceOn(old, "old").also { it.addRepo(repo()); it.sync(); it.addRepo(repo()); it.sync() }

        assertTrue(modern.gzipPosts >= 1, "gzip to a v3 server (after it told the app it is one)")
        assertEquals(0, old.gzipPosts, "plain to a v2 server")
        assertTrue(old.ackRequests.isEmpty(), "and no acknowledgement it would not know what to do with")
    }
}
