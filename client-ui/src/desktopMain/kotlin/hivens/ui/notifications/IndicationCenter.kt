package hivens.ui.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Passive UI signals -- the dots, spinners, glows that live inline
 * in the affordances they describe ([PackCard] glow when an instance
 * is launching, sidebar badge for "update available", header chip
 * for mirror health). Drivers push updates; renderers subscribe to
 * the per-affordance flow that matches their slot.
 *
 * Separation from [NotificationCenter] is deliberate: indications
 * are passive and ambient, notifications demand attention. A pack
 * preparing to launch has both -- a quiet glow on its Library card
 * (indication) AND a counter-grouped progress toast in the stack
 * (notification). Drivers update each layer independently.
 *
 * Per-pack flows are created lazily on first read and cached so a
 * renderer that subscribes before any driver has pushed gets a
 * stable StateFlow handle. The cache never shrinks within a
 * process lifetime; in practice the set of pack ids is bounded
 * (Library never holds thousands), so the leak is academic.
 */
class IndicationCenter {

    /** State of a pack launch as it should appear on the pack card. */
    sealed class LaunchIndication {
        data object Preparing : LaunchIndication()
        /** Progress in 0..1, or null for indeterminate. */
        data class Downloading(val progress: Float?) : LaunchIndication()
        data object Running : LaunchIndication()
        data object Failed : LaunchIndication()
    }

    enum class MirrorHealth { Ok, Degraded, Unreachable }

    private val launchFlows: ConcurrentHashMap<String, MutableStateFlow<LaunchIndication?>> =
        ConcurrentHashMap()
    private val updateFlows: ConcurrentHashMap<String, MutableStateFlow<Boolean>> =
        ConcurrentHashMap()
    private val _mirrorHealth = MutableStateFlow(MirrorHealth.Ok)
    val mirrorHealth: StateFlow<MirrorHealth> = _mirrorHealth.asStateFlow()

    /**
     * Per-pack launch indication. Renderers in `PackCard`,
     * `PackDetail` hero, and the optional running-glow overlay all
     * subscribe to the same flow for a given `packInstanceId`.
     */
    fun launchIndication(packInstanceId: String): StateFlow<LaunchIndication?> =
        launchFlows.computeIfAbsent(packInstanceId) { MutableStateFlow(null) }.asStateFlow()

    fun setLaunchIndication(packInstanceId: String, value: LaunchIndication?) {
        launchFlows.computeIfAbsent(packInstanceId) { MutableStateFlow(null) }.value = value
    }

    /**
     * Per-pack "update available" badge. True = mirror has a
     * pack_version newer than what the installed instance is pinned
     * to. PackUpdateDriver (future) flips this on mirror polls.
     */
    fun updateAvailable(packInstanceId: String): StateFlow<Boolean> =
        updateFlows.computeIfAbsent(packInstanceId) { MutableStateFlow(false) }.asStateFlow()

    fun setUpdateAvailable(packInstanceId: String, available: Boolean) {
        updateFlows.computeIfAbsent(packInstanceId) { MutableStateFlow(false) }.value = available
    }

    fun setMirrorHealth(value: MirrorHealth) {
        _mirrorHealth.update { value }
    }
}
