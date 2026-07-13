package hivens.ui.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val launchFlows: ConcurrentHashMap<String, MutableStateFlow<LaunchIndication?>> =
        ConcurrentHashMap()

    fun launchIndication(packInstanceId: String): StateFlow<LaunchIndication?> =
        launchFlows.computeIfAbsent(packInstanceId) { MutableStateFlow(null) }.asStateFlow()

    fun setLaunchIndication(packInstanceId: String, value: LaunchIndication?) {
        launchFlows.computeIfAbsent(packInstanceId) { MutableStateFlow(null) }.value = value
    }
}
