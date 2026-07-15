package hivens.ui.widgets.about

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.core.data.LauncherUpdate
import hivens.core.jvm.SystemHardware

// Tri-state machine the update widget reads + the surface mutates
// via the triggerUpdateCheck lambda. Public so the widget files
// across this package can branch on it; nothing outside about
// touches this type.
sealed class UpdateCheckState {
    object Idle     : UpdateCheckState()
    object Checking : UpdateCheckState()
    object UpToDate : UpdateCheckState()
    data class Available(val update: LauncherUpdate) : UpdateCheckState()
    data class Error(val message: String)            : UpdateCheckState()
}

// Surface-scoped state + pre-computed values the about widgets
// share. systemRam + displayRes are queried once at surface mount
// (expensive reflection on the OS bean + AWT toolkit call) and
// passed to widgets. updateState + showUpdateDialog are
// MutableState so the update.panel widget can observe / mutate via
// the surface's triggerUpdateCheck lambda. Plain class -- holds
// MutableState references, so generated equals / hashCode would
// lie about value semantics.
class AboutContext(
    val updateState: MutableState<UpdateCheckState>,
    val showUpdateDialog: MutableState<Boolean>,
    val triggerUpdateCheck: () -> Unit,
    val systemRam: Int,
    val swapMb: Int?,
    val cpu: SystemHardware.CpuInfo,
    val displayInfo: String,
    val renderer: String,
)

val LocalAboutContext: ProvidableCompositionLocal<AboutContext> =
    staticCompositionLocalOf {
        error("LocalAboutContext not provided -- render inside AboutSurface")
    }

internal val STUB_ABOUT: AboutContext = AboutContext(
    updateState        = mutableStateOf(UpdateCheckState.Idle),
    showUpdateDialog   = mutableStateOf(false),
    triggerUpdateCheck = {},
    systemRam          = 0,
    swapMb             = null,
    cpu                = SystemHardware.CpuInfo(null, 0, null, null),
    displayInfo        = "Unknown",
    renderer           = "Unknown",
)
