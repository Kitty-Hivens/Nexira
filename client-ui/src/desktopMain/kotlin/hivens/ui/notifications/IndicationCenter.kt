package hivens.ui.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

// Per-pack flows created lazily and cached; never shrinks (bounded
// by Library's pack count, leak is academic).
class IndicationCenter {

    sealed class LaunchIndication {
        data object Preparing : LaunchIndication()
        // progress: null = indeterminate
        data class Downloading(val progress: Float?) : LaunchIndication()
        data object Running : LaunchIndication()
        data object Failed : LaunchIndication()
    }

    enum class MirrorHealth { Ok, Degraded, Unreachable } // TODO: Class "Degraded" is never used, Class "Unreachable" is never used

    private val launchFlows: ConcurrentHashMap<String, MutableStateFlow<LaunchIndication?>> =
        ConcurrentHashMap()
    private val updateFlows: ConcurrentHashMap<String, MutableStateFlow<Boolean>> =
        ConcurrentHashMap()
    private val _mirrorHealth = MutableStateFlow(MirrorHealth.Ok)
    val mirrorHealth: StateFlow<MirrorHealth> = _mirrorHealth.asStateFlow() // TODO: Property "mirrorHealth" is never used

    fun launchIndication(packInstanceId: String): StateFlow<LaunchIndication?> = // TODO: Function "launchIndication" is never used
        launchFlows.computeIfAbsent(packInstanceId) { MutableStateFlow(null) }.asStateFlow()

    fun setLaunchIndication(packInstanceId: String, value: LaunchIndication?) {
        launchFlows.computeIfAbsent(packInstanceId) { MutableStateFlow(null) }.value = value
    }

    fun updateAvailable(packInstanceId: String): StateFlow<Boolean> = // TODO: Function "updateAvailable" is never used
        updateFlows.computeIfAbsent(packInstanceId) { MutableStateFlow(false) }.asStateFlow()

    fun setUpdateAvailable(packInstanceId: String, available: Boolean) { // TODO: Function "setUpdateAvailable" is never used
        updateFlows.computeIfAbsent(packInstanceId) { MutableStateFlow(false) }.value = available
    }

    fun setMirrorHealth(value: MirrorHealth) { // TODO: Function "setMirrorHealth" is never used
        _mirrorHealth.update { value }
    }
}
