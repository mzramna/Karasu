package karasu.data.suwayomi

import eu.kanade.tachiyomi.network.await
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** What the transport needs to know about the server; read again on every call so edits apply at once. */
data class ServerConfig(
    val primaryUrl: String,
    val secondaryUrl: String,
    val username: String,
    val password: String,
)

/** A response together with the address that produced it. The caller owns (and must close) [response]. */
class ServerResponse(val response: Response, val origin: ServerOrigin, val base: HttpUrl)

class ServerNotConfiguredException : IOException("Suwayomi server address is not set")

/** Every configured address failed. The message lists each one, which is what the settings screen shows. */
class ServerUnreachableException(failures: List<Pair<ServerOrigin, IOException>>) : IOException(
    failures.joinToString("; ") { (origin, e) ->
        "${origin.name.lowercase()}: ${e.message ?: e::class.simpleName}"
    },
    failures.lastOrNull()?.second,
)

/**
 * Sends requests to the server's primary address and, when that cannot be reached, to the
 * secondary one — the LAN-first / VPN-fallback pattern.
 *
 * Failover is decided on the *connection* only: any [IOException] before a response arrived moves
 * on to the next address. An HTTP error status is a real answer from a real server and is never
 * retried elsewhere. The address that last worked is remembered and tried first, except that the
 * primary is re-checked after [PRIMARY_RECHECK_MS] so the app drifts back to the LAN on its own.
 *
 * The connect timeout of the primary is deliberately short (3 s): at home it either answers
 * instantly or not at all, and waiting longer only delays the fallback.
 */
class ServerTransport(
    private val config: () -> ServerConfig,
    baseClient: OkHttpClient,
    private val reachability: ServerReachability,
    private val onGoodOrigin: (ServerOrigin) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {

    // Both clients: no response cache (a stale listing is worse than a slow one) and no overall
    // call timeout, since a backup or an archive is legitimately a long transfer. The read timeout
    // still bounds how long the connection may sit silent.
    private val primaryClient = baseClient.newBuilder()
        .cache(null)
        .connectTimeout(PRIMARY_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val secondaryClient = primaryClient.newBuilder()
        .connectTimeout(SECONDARY_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var lastGood: ServerOrigin? = null

    @Volatile
    private var secondarySince = 0L

    val isConfigured: Boolean get() = config().primaryUrl.isNotBlank()

    /**
     * Runs [buildRequest] against each address in order until one answers.
     *
     * [buildRequest] is called once per attempt with that address's base URL, so a body that
     * streams from disk is reopened rather than replayed.
     */
    suspend fun execute(buildRequest: (HttpUrl) -> Request): ServerResponse {
        val cfg = config()
        val candidates = orderedOrigins(cfg)
        if (candidates.isEmpty()) throw ServerNotConfiguredException()

        val failures = mutableListOf<Pair<ServerOrigin, IOException>>()
        for ((origin, base) in candidates) {
            val request = buildRequest(base).withAuth(cfg)
            val client = if (origin == ServerOrigin.PRIMARY) primaryClient else secondaryClient
            try {
                val response = client.newCall(request).await()
                markGood(origin, triedPrimaryFirst = candidates.first().first == ServerOrigin.PRIMARY)
                return ServerResponse(response, origin, base)
            } catch (e: IOException) {
                failures += origin to e
            }
        }
        reachability.reportUnreachable()
        throw ServerUnreachableException(failures)
    }

    private fun markGood(origin: ServerOrigin, triedPrimaryFirst: Boolean) {
        // Landing on the secondary after the primary just failed restarts the stay-on-secondary
        // window, otherwise every call after the first re-check would pay the primary's timeout again.
        if (origin == ServerOrigin.SECONDARY && (lastGood != ServerOrigin.SECONDARY || triedPrimaryFirst)) {
            secondarySince = now()
        }
        if (lastGood != origin) onGoodOrigin(origin)
        lastGood = origin
        reachability.reportReachable(origin)
    }

    /** The addresses to try, in order. Blank or unparsable ones are dropped, not errors. */
    internal fun orderedOrigins(cfg: ServerConfig): List<Pair<ServerOrigin, HttpUrl>> {
        val primary = parseBase(cfg.primaryUrl)?.let { ServerOrigin.PRIMARY to it }
        val secondary = parseBase(cfg.secondaryUrl)?.let { ServerOrigin.SECONDARY to it }
        val both = listOfNotNull(primary, secondary)
        if (both.size < 2) return both

        // Only this process's own experience counts. A cold start always tries the primary first:
        // it costs at most the 3 s connect timeout away from home, and at home it is the right answer.
        val stayOnSecondary = lastGood == ServerOrigin.SECONDARY && now() - secondarySince < PRIMARY_RECHECK_MS
        return if (stayOnSecondary) both.reversed() else both
    }

    private fun Request.withAuth(cfg: ServerConfig): Request {
        if (cfg.username.isBlank()) return this
        return newBuilder()
            .header("Authorization", Credentials.basic(cfg.username, cfg.password, Charsets.UTF_8))
            .build()
    }

    companion object {
        const val PRIMARY_CONNECT_TIMEOUT_S = 3L
        const val SECONDARY_CONNECT_TIMEOUT_S = 10L
        const val READ_TIMEOUT_S = 30L
        const val PRIMARY_RECHECK_MS = 2 * 60 * 1000L

        /**
         * Turns what a person typed into a base URL.
         *
         * A missing scheme is assumed to be `http`, since the typical address is a bare LAN
         * `host:port`; trailing slashes are dropped so paths can be appended without doubling.
         * A subpath (`https://example.com/suwayomi`) is kept.
         */
        fun parseBase(raw: String): HttpUrl? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            return withScheme.toHttpUrlOrNull()
        }
    }
}
