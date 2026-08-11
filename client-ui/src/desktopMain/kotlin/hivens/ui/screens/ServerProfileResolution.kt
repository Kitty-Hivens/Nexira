package hivens.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import hivens.core.api.interfaces.IServerListService
import hivens.core.api.model.ServerProfile
import hivens.launcher.ServerListCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** Where a server-scoped screen is in resolving the roster entry it was opened for. */
sealed interface ServerResolution {
    data object Loading : ServerResolution

    /** The roster has been read and this id is not in it. */
    data object NotFound : ServerResolution

    data class Ready(val server: ServerProfile) : ServerResolution
}

/**
 * The roster entry for [serverId] as it stands now.
 *
 * The server screens used to be handed the [ServerProfile] the grid was showing
 * when the row was clicked, carried inside the navigation entry itself. That copy
 * aged with every roster fetch, so a screen opened before an update went on
 * showing an address, a version and a checksum set the roster had moved past.
 *
 * Cache first so the screen paints without waiting on the network, then the live
 * roster -- which is served from the session's own in-memory snapshot when there
 * is one, so returning to a screen costs nothing.
 */
@Composable
fun rememberServerResolution(serverId: String): ServerResolution {
    val cache: ServerListCacheStore = koinInject()
    val service: IServerListService = koinInject()
    val resolution by produceState<ServerResolution>(ServerResolution.Loading, serverId) {
        value = ServerResolution.Loading
        withContext(Dispatchers.IO) { cache.load() }
            .firstOrNull { it.assetDir == serverId }
            ?.let { value = ServerResolution.Ready(it) }
        // An unreachable roster comes back as an EMPTY list rather than a failure
        // -- the service swallows its own errors -- so an empty answer is no answer
        // at all. Reading it as "the server is gone" is what would throw an offline
        // user out of a screen the cache had just painted for them.
        val live = runCatching {
            runInterruptible(Dispatchers.IO) { service.fetchDashboardData().get() }
        }.getOrNull()?.servers?.takeIf { it.isNotEmpty() }
        val fresh = live?.firstOrNull { it.assetDir == serverId }
        value = when {
            fresh != null -> ServerResolution.Ready(fresh)
            // A roster that answered without this server is not proof it is gone --
            // a truncated or partial response reads the same -- and leaving is not
            // free: the settings screen saves on the way out, and a bounce takes
            // the form with it. What the cache gave stands.
            value is ServerResolution.Ready -> value
            // Nothing cached and nothing served: there is no screen to build, and a
            // spinner that never ends says less than leaving.
            else -> ServerResolution.NotFound
        }
    }
    return resolution
}
