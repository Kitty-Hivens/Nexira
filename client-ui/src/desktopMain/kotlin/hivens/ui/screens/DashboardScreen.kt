package hivens.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import hivens.core.api.model.ServerProfile
import hivens.core.data.SessionData
import hivens.ui.puppet.PuppetScreen
import hivens.ui.widgets.home.classic.HomeClassicContext
import hivens.ui.widgets.home.classic.LocalHomeClassicContext
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId

/**
 * Classic dashboard surface. Provides navigation context for the
 * monolithic home.classic.content widget; the widget owns layout and
 * state for the entire legacy dashboard.
 *
 * Surface signature stays compatible with AppLayout so the existing
 * callback wiring keeps working.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DashboardScreen(
    session: SessionData,
    initialSelectedServer: ServerProfile?,
    onServerSelected: (ServerProfile) -> Unit,
    onSessionUpdated: (SessionData) -> Unit,
    onCloseApp: () -> Unit,
    onOpenServerSettings: (ServerProfile) -> Unit,
    onOpenDetails: (ServerProfile) -> Unit,
) {
    PuppetScreen("Dashboard")

    val ctx = remember(
        session, initialSelectedServer, onServerSelected, onSessionUpdated,
        onCloseApp, onOpenServerSettings, onOpenDetails,
    ) {
        HomeClassicContext(
            session               = session,
            initialSelectedServer = initialSelectedServer,
            onServerSelected      = onServerSelected,
            onSessionUpdated      = onSessionUpdated,
            onCloseApp            = onCloseApp,
            onOpenServerSettings  = onOpenServerSettings,
            onOpenDetails         = onOpenDetails,
        )
    }
    CompositionLocalProvider(LocalHomeClassicContext provides ctx) {
        SlotRenderer(SurfaceId(SURFACE), SlotId("main"), Modifier.fillMaxSize())
    }
}

private const val SURFACE = "home.classic"
