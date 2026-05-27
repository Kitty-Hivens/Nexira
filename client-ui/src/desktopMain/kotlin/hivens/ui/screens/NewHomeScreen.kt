package hivens.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.core.data.SessionData
import hivens.ui.AppState
import hivens.ui.Screen
import hivens.ui.puppet.PuppetScreen
import hivens.ui.widgets.home.new.HomeNewContext
import hivens.ui.widgets.home.new.LocalHomeNewContext
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId

/**
 * Widget-composed home surface. Kernel-3 ships a minimal prototype --
 * welcome banner + recent-packs row + quick-launch -- proving the
 * slot machinery for a brand-new (rather than legacy-wrapped) surface.
 * Content grows in later phases as user-customization arrives.
 */
@Composable
fun NewHomeScreen(
    appState: AppState,
    onScreenChange: (Screen) -> Unit,
    onSessionUpdated: (SessionData) -> Unit,
) {
    PuppetScreen("NewHome")

    val ctx = remember(appState, onScreenChange, onSessionUpdated) {
        HomeNewContext(
            appState         = appState,
            onScreenChange   = onScreenChange,
            onSessionUpdated = onSessionUpdated,
        )
    }
    CompositionLocalProvider(LocalHomeNewContext provides ctx) {
        Column(
            modifier            = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SlotRenderer(SurfaceId(SURFACE), SlotId("main"))
        }
    }
}

private const val SURFACE = "home.new"
