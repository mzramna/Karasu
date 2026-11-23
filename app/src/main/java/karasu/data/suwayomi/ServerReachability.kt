package karasu.data.suwayomi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which of the two configured addresses answered. */
enum class ServerOrigin { PRIMARY, SECONDARY }

sealed interface ServerState {
    /** Nothing has been tried yet, or the network changed since the last attempt. */
    data object Unknown : ServerState

    data class Online(val origin: ServerOrigin) : ServerState

    data object Offline : ServerState
}

/**
 * What the app currently believes about the server, fed by the results of real requests.
 *
 * It is a belief, not a probe: it only changes when a request succeeds or every address failed.
 * A network change resets it to [ServerState.Unknown], because "offline" on the train says
 * nothing about the home Wi-Fi.
 */
class ServerReachability(private val now: () -> Long = System::currentTimeMillis) {

    private val _state = MutableStateFlow<ServerState>(ServerState.Unknown)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    @Volatile
    private var offlineSince = 0L

    fun reportReachable(origin: ServerOrigin) {
        offlineSince = 0L
        _state.value = ServerState.Online(origin)
    }

    fun reportUnreachable() {
        if (_state.value !is ServerState.Offline) offlineSince = now()
        _state.value = ServerState.Offline
    }

    fun reset() {
        offlineSince = 0L
        _state.value = ServerState.Unknown
    }

    /**
     * Whether an optional request should not even be tried.
     *
     * A failed attempt costs the connect timeout, and the downloader would pay it once per
     * chapter. Within [OFFLINE_BACKOFF_MS] of a failure everything optional skips the server;
     * after that one request is allowed through to find out whether it is back.
     */
    fun shouldSkipOptional(): Boolean =
        _state.value is ServerState.Offline && now() - offlineSince < OFFLINE_BACKOFF_MS

    /** Resets on every network change so a stale "offline" never outlives the network it was seen on. */
    fun attach(context: Context) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = reset()
                },
            )
        }.onFailure { Logger.w(it) { "Network callback not registered, relying on request results" } }
    }

    private companion object {
        const val OFFLINE_BACKOFF_MS = 30_000L
    }
}
