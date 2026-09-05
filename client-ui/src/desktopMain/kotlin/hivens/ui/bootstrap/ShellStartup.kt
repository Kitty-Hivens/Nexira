package hivens.ui.bootstrap

import hivens.core.api.model.ServerProfile
import hivens.core.api.rosterAfterFetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ShellStartup")

/**
 * Which background work the session is allowed to start, read from settings
 * once at bring-up.
 *
 * The auto-* flags are two independent axes: mirror pack builds and SmartyCraft
 * clients are updated by different services, are opted into separately, and do
 * not share a reliability record.
 */
data class StartupPolicy(
    val trayEnabled: Boolean,
    val notifierEnabled: Boolean,
    val autoSyncAllPacks: Boolean,
    val autoUpdatePacks: Boolean,
)

/**
 * What the shell does once, on the way up: bring the tray and the notifier
 * online, work out the server roster, and start the background services the
 * settings allow.
 *
 * Lifted out of a hundred-line `LaunchedEffect`. The parts that touch a
 * process-wide singleton -- the tray library, the notifier -- arrive as
 * functions rather than as the objects themselves, so the sequence can be run
 * against fakes. That is the whole point: the order here is load-bearing and
 * was previously unverifiable.
 *
 * Order, and why:
 *  1. Tray and notifier first, because the window's close path asks the tray
 *     whether hiding is possible.
 *  2. If the tray did not come up, put the window back on screen -- a user who
 *     closed to tray during init would otherwise be left with a running
 *     process and no reachable UI.
 *  3. The roster, because auto-sync needs it.
 *  4. The background services, each on the app scope so they outlive this
 *     composition but still die with the process.
 */
class ShellStartup(
    private val policy: StartupPolicy,
    private val bringUpTray: suspend (icon: ByteArray) -> Unit,
    private val bringUpNotifier: suspend (icon: ByteArray) -> Unit,
    private val readIcon: suspend (path: String) -> ByteArray,
    private val trayIsSupported: () -> Boolean,
    private val showWindow: () -> Unit,
    private val cachedRoster: suspend () -> List<ServerProfile>,
    private val fetchRoster: suspend () -> List<ServerProfile>,
    private val syncAll: suspend (List<ServerProfile>) -> Unit,
    private val recoverInterrupted: suspend () -> Unit,
    private val autoUpdatePacks: suspend () -> Unit,
    private val appScope: CoroutineScope,
) {

    suspend fun run(windowVisible: () -> Boolean) {
        bringUpTrayAndNotifier()

        if (!trayIsSupported() && !windowVisible()) showWindow()

        val roster = resolveRoster()

        // Fire-and-forget on the app scope: these outlive a composition reset
        // (a locale switch, a crash reload) but are cancelled on JVM exit,
        // unlike a GlobalScope launch which would leak handles past close.
        if (policy.autoSyncAllPacks && roster.isNotEmpty()) appScope.launch { syncAll(roster) }

        // Runs regardless of the auto-update opt-in: an update a hard crash
        // interrupted leaves a half-applied instance, and that has to be
        // repaired before anything else touches it.
        appScope.launch { recoverInterrupted() }

        if (policy.autoUpdatePacks) appScope.launch { autoUpdatePacks() }
    }

    /**
     * Tray and notifier off one icon, falling back to the second asset when the
     * first cannot be read. The fallback covers a packaging slip rather than a
     * runtime condition, so a failure of both is logged and swallowed: no tray
     * is a degraded launcher, not a dead one.
     */
    private suspend fun bringUpTrayAndNotifier() {
        try {
            bringUp(readIcon(PRIMARY_ICON))
        } catch (e: CancellationException) {
            throw e
        } catch (first: Exception) {
            log.warn("Tray/notifier bring-up failed on the primary icon; retrying with the fallback", first)
            try {
                bringUp(readIcon(FALLBACK_ICON))
            } catch (e: CancellationException) {
                throw e
            } catch (second: Exception) {
                log.warn("Tray/notifier unavailable this session", second)
            }
        }
    }

    private suspend fun bringUp(icon: ByteArray) {
        if (policy.trayEnabled) bringUpTray(icon)
        if (policy.notifierEnabled) bringUpNotifier(icon)
    }

    /**
     * The roster to work with. A fetch that throws falls back to the cache; a
     * fetch that returns empty is judged by [rosterAfterFetch], since the
     * upstream answers empty for an outage too.
     */
    private suspend fun resolveRoster(): List<ServerProfile> {
        val cached = cachedRoster()
        return try {
            rosterAfterFetch(fetched = fetchRoster(), cached = cached)
        } catch (e: CancellationException) {
            // Composition leave / locale switch / exit mid-fetch propagates:
            // turning it into "outage" would defeat structured concurrency.
            throw e
        } catch (e: Exception) {
            log.warn("Server roster fetch failed; falling back to the cached list", e)
            cached
        }
    }

    private companion object {
        const val PRIMARY_ICON = "drawable/favicon.png"
        const val FALLBACK_ICON = "drawable/icon.png"
    }
}
