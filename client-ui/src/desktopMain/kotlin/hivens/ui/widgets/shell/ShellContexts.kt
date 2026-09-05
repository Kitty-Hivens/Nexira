package hivens.ui.widgets.shell

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.core.data.SessionData
import hivens.ui.AppState
import hivens.ui.Screen

// AppShell rails carry navigation state per render. The shell composable
// computes them once and provides via these locals; rail widgets read
// without re-resolving.
data class LeftRailContext(
    val currentScreen: Screen,
    val isAuthenticated: Boolean,
    val onScreenChange: (Screen) -> Unit,
    /** What a rail entry does: switch to a fresh context rather than push. */
    val onSwitchTab: (Screen) -> Unit,
    val onLogout: () -> Unit,
)

val LocalLeftRailContext: ProvidableCompositionLocal<LeftRailContext> =
    staticCompositionLocalOf {
        error("LocalLeftRailContext not provided -- mount inside AppSidebar")
    }

data class RightRailContext(
    val appState: AppState,
    val onLogin: (SessionData) -> Unit,
    val onLogout: () -> Unit,
    val sslBypass: Boolean,
)

val LocalRightRailContext: ProvidableCompositionLocal<RightRailContext> =
    staticCompositionLocalOf {
        error("LocalRightRailContext not provided -- mount inside RightPanel")
    }
