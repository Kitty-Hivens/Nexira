package hivens.ui.widgets.home.classic

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.core.api.model.ServerProfile
import hivens.core.data.SessionData

// Navigation context for the monolithic classic-home widget. Provided
// by DashboardScreen, consumed by HomeClassicContent. Carries the
// per-render values that cannot live in Koin (live session, navigation
// callbacks routing back into AppShell).
data class HomeClassicContext(
    val session: SessionData,
    val initialSelectedServer: ServerProfile?,
    val onServerSelected: (ServerProfile) -> Unit,
    val onSessionUpdated: (SessionData) -> Unit,
    val onOpenServerSettings: (ServerProfile) -> Unit,
    val onOpenDetails: (ServerProfile) -> Unit,
)

val LocalHomeClassicContext: ProvidableCompositionLocal<HomeClassicContext> =
    staticCompositionLocalOf {
        error("LocalHomeClassicContext not provided -- mount inside DashboardScreen")
    }
