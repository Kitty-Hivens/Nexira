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

class SessionRegistry(
    private val appScope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
) {

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

    // Replaces on duplicate id: controller's re-entry guard rejects
    // concurrent launches, so a duplicate here means the prior session
    // already exited and the new arrival is authoritative.
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

        uptimeJobs.remove(packInstanceId)?.cancel()
        uptimeJobs[packInstanceId] = appScope.launch(Dispatchers.Default) {
            while (true) {
                uptime.value = Duration.between(startedAt, clock())
                delay(1_000L)
            }
        }
        return session
    }

    fun unregister(packInstanceId: String) {
        uptimeJobs.remove(packInstanceId)?.cancel()
        _active.value = _active.value - packInstanceId
    }
}
