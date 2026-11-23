package karasu.domain.suwayomi.interactor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.TimeUnit

/**
 * The real WebUI, driven in a real browser through `scripts/full-stack/web-driver.mjs` (Playwright).
 * Each call is one thing a person would do in the WebUI - click through a screen - and answers with what
 * the driver saw as JSON. Only the full-stack battery uses it; the pipelines never start a browser here.
 */
class WebUi(
    private val driver: String = System.getenv("KARASU_FULLSTACK_DRIVER").orEmpty(),
    private val nodePath: String = System.getenv("KARASU_FULLSTACK_NODE_PATH").orEmpty(),
    private val url: String = Live.url,
) {
    val isAvailable: Boolean get() = driver.isNotEmpty()

    /** Runs a driver action and returns its JSON answer; a failing action throws with the driver's message. */
    fun run(action: String, vararg args: String): JsonElement {
        val command = listOf("node", driver, action, *args)
        val builder = ProcessBuilder(command).redirectErrorStream(false)
        builder.environment().apply {
            put("KARASU_URL", url)
            put("KARASU_USER", Live.user)
            put("KARASU_PASS", Live.pass)
            if (nodePath.isNotEmpty()) put("NODE_PATH", nodePath)
        }
        val process = builder.start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("web driver '$action' timed out")
        }
        check(process.exitValue() == 0) { "web driver '$action ${args.joinToString(" ")}' failed: ${err.ifBlank { out }}" }
        return Json.parseToJsonElement(out.lines().last { it.isNotBlank() })
    }
}
