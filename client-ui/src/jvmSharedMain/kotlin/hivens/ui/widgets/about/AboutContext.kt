package hivens.ui.widgets.about

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.core.data.LauncherUpdate
import hivens.core.jvm.SystemHardware

// Tri-state machine the update widget reads and the surface drives.
// Public so the widget files across this package can branch on it;
// nothing outside about touches this type.
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
// MutableState so the update.panel widget can observe the result
// and open the dialog.
//
// The check itself is not here. It runs on a timer the surface owns and
// there is no manual check button, so the trigger stayed a lambda the
// surface called on itself -- carrying a copy in the context only made it
// look like a widget could fire one. Plain class -- holds MutableState
// references, so generated equals / hashCode would lie about value
// semantics.
class AboutContext(
    val updateState: MutableState<UpdateCheckState>,
    val showUpdateDialog: MutableState<Boolean>,
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
    systemRam          = 0,
    swapMb             = null,
    cpu                = SystemHardware.CpuInfo(null, 0, null, null),
    displayInfo        = "Unknown",
    renderer           = "Unknown",
)
