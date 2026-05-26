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

    enum class MirrorHealth { Ok, Degraded, Unreachable }

    private val launchFlows: ConcurrentHashMap<String, MutableStateFlow<LaunchIndication?>> =
        ConcurrentHashMap()
    private val updateFlows: ConcurrentHashMap<String, MutableStateFlow<Boolean>> =
        ConcurrentHashMap()
    private val _mirrorHealth = MutableStateFlow(MirrorHealth.Ok)
    val mirrorHealth: StateFlow<MirrorHealth> = _mirrorHealth.asStateFlow()

    fun launchIndication(packInstanceId: String): StateFlow<LaunchIndication?> =
        launchFlows.computeIfAbsent(packInstanceId) { MutableStateFlow(null) }.asStateFlow()

    fun setLaunchIndication(packInstanceId: String, value: LaunchIndication?) {
        launchFlows.computeIfAbsent(packInstanceId) { MutableStateFlow(null) }.value = value
    }

    fun updateAvailable(packInstanceId: String): StateFlow<Boolean> =
        updateFlows.computeIfAbsent(packInstanceId) { MutableStateFlow(false) }.asStateFlow()

    fun setUpdateAvailable(packInstanceId: String, available: Boolean) {
        updateFlows.computeIfAbsent(packInstanceId) { MutableStateFlow(false) }.value = available
    }

    fun setMirrorHealth(value: MirrorHealth) {
        _mirrorHealth.update { value }
    }
}
