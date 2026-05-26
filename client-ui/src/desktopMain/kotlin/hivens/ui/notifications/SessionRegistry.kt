package hivens.ui.notifications

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Live registry of running pack-spawned processes. A "session" is
 * one [Process] tied to one [PackInstance]; the launcher's existing
 * re-entry guard caps the registry at one concurrent session per
 * pack, so the registry is keyed by `packInstanceId`.
 *
 * Separate from both [NotificationCenter] and [IndicationCenter]
 * because session state is PERSISTENT (the chip stays visible while
 * the game runs, possibly for hours) -- not transient like a toast
 * and not contextual like a glow. The Library sidebar's "Active
 * sessions" section is the canonical render surface.
 *
 * The registry does not own the Process; it holds a weak control
 * handle ([ActiveSession.abort]) that delegates to the existing
 * [hivens.launcher.launch.LauncherController.abort]. Killing a
 * session from the chip is identical to clicking Abort on the
 * legacy launch panel.
 */
class SessionRegistry(
    private val appScope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
) {

    /**
     * One live session. `uptime` ticks every second while the
     * session is registered; once [SessionRegistry.unregister]
     * fires, the flow stops emitting and the chip disappears.
     */
    data class ActiveSession(
        val packInstanceId: String,
        val packDisplayName: String,
        val packIconUrl: String?,
        val startedAt: Instant,
        val uptime: StateFlow<Duration>,
        val abort: () -> Unit,
        val showConsole: () -> Unit,
    )

    private val _active = MutableStateFlow<Map<String, ActiveSession>>(emptyMap())
    val active: StateFlow<Map<String, ActiveSession>> = _active.asStateFlow()

    private val uptimeJobs: MutableMap<String, Job> = mutableMapOf()

    /**
     * Register a session. Returns the registered [ActiveSession] so
     * the caller can hand a stable reference to whichever UI surface
     * needs the abort / showConsole callbacks (notification toast's
     * action button reuses the same instances the chip renders).
     *
     * Calling `register` for an id already present REPLACES the
     * entry -- the previous launch must have already exited and the
     * controller's re-entry guard rejected anything in between, so
     * we treat the new arrival as authoritative.
     */
    fun register(
        packInstanceId: String,
        packDisplayName: String,
        packIconUrl: String?,
        abort: () -> Unit,
        showConsole: () -> Unit,
    ): ActiveSession {
        val startedAt = clock()
        val uptime = MutableStateFlow(Duration.ZERO)
        val session = ActiveSession(
            packInstanceId   = packInstanceId,
            packDisplayName  = packDisplayName,
            packIconUrl      = packIconUrl,
            startedAt        = startedAt,
            uptime           = uptime.asStateFlow(),
            abort            = abort,
            showConsole      = showConsole,
        )
        _active.value = _active.value + (packInstanceId to session)

        // Stop any prior ticker (replace semantics) before starting
        // a fresh one for this session.
        uptimeJobs.remove(packInstanceId)?.cancel()
        uptimeJobs[packInstanceId] = appScope.launch(Dispatchers.Default) {
            while (true) {
                uptime.value = Duration.between(startedAt, clock())
                delay(1_000L)
            }
        }
        return session
    }

    /**
     * Remove a session from the registry. Idempotent; calling for an
     * unknown id is a no-op. Cancels the per-session uptime ticker
     * so it does not leak past the chip's lifetime.
     */
    fun unregister(packInstanceId: String) {
        uptimeJobs.remove(packInstanceId)?.cancel()
        _active.value = _active.value - packInstanceId
    }
}
