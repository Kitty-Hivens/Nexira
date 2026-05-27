package hivens.ui.widgets.home.new

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.core.data.SessionData
import hivens.ui.AppState
import hivens.ui.Screen

// Navigation context for the new prototype home surface. Widgets in
// home.new.main read live appState + nav dispatcher. session lifts
// from AppState.Authenticated where applicable; widgets that need it
// guard on the cast themselves.
data class HomeNewContext(
    val appState: AppState,
    val onScreenChange: (Screen) -> Unit,
    val onSessionUpdated: (SessionData) -> Unit,
)

val LocalHomeNewContext: ProvidableCompositionLocal<HomeNewContext> =
    staticCompositionLocalOf {
        error("LocalHomeNewContext not provided -- mount inside NewHomeScreen")
    }
