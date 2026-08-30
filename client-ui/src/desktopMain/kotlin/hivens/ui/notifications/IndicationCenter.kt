package hivens.ui.notifications

import hivens.core.launch.LaunchControlMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

// Per-pack launch-state flows created lazily and cached; never shrinks (bounded
// by Library's pack count, leak is academic). LaunchDriver writes; PackCard reads.
class IndicationCenter {

    sealed class LaunchIndication {
        data object Preparing : LaunchIndication()
        // progress: null = indeterminate
        data class Downloading(val progress: Float?) : LaunchIndication()
        data object Running : LaunchIndication()
        data object Failed : LaunchIndication()
    }

    companion object {
        /**
         * The per-pack indication read as the same control mode the whole app
         * uses. Lives here rather than in `client-core` only because
         * [LaunchIndication] does -- the reading itself is the domain's, and
         * the mapping is deliberately identical to `LaunchState.controlMode()`.
         *
         * Null means this pack has no launch in flight, which is a plain Play.
         */
        fun LaunchIndication?.controlMode(): LaunchControlMode = when (this) {
            null, LaunchIndication.Failed -> LaunchControlMode.Play
            LaunchIndication.Preparing,
            is LaunchIndication.Downloading -> LaunchControlMode.Wait
            LaunchIndication.Running -> LaunchControlMode.Stop
        }
    }

    private val launchFlows: ConcurrentHashMap<String, MutableStateFlow<LaunchIndication?>> =
        ConcurrentHashMap()

    /** The same instance for the same pack, every call: a reader collects it
     *  directly and a fresh wrapper per call would re-subscribe per recomposition. */
    fun launchIndication(packInstanceId: String): StateFlow<LaunchIndication?> =
        launchFlows.computeIfAbsent(packInstanceId) { MutableStateFlow(null) }

    fun setLaunchIndication(packInstanceId: String, value: LaunchIndication?) {
        launchFlows.computeIfAbsent(packInstanceId) { MutableStateFlow(null) }.value = value
    }
}
