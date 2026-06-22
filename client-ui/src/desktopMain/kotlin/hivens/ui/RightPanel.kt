package hivens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import hivens.core.data.SessionData
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.shell.LocalRightRailContext
import hivens.ui.widgets.shell.RightRailContext
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId

/**
 * Right-side panel. Surface composable: owns the column container; the
 * news feed resolves through SlotRenderer against the layout graph.
 * Sign-in moved to the Profile section, so the rail no longer carries an
 * auth slot.
 */
@Composable
fun RightPanel(
    appState: AppState,
    onLogin: (SessionData) -> Unit,
    onLogout: () -> Unit,
    sslBypass: Boolean,
    modifier: Modifier = Modifier,
) {
    val ctx = remember(appState, onLogin, onLogout, sslBypass) {
        RightRailContext(
            appState  = appState,
            onLogin   = onLogin,
            onLogout  = onLogout,
            sslBypass = sslBypass,
        )
    }
    CompositionLocalProvider(LocalRightRailContext provides ctx) {
        Column(modifier = modifier.background(NxTheme.colors.background)) {
            SlotRenderer(SurfaceId(SURFACE), SlotId("news"), Modifier.weight(1f).fillMaxWidth())
            // Bottom slot: the message-history widget seeds here by default; the
            // news slot takes the weight so this stays pinned to the bottom.
            SlotRenderer(SurfaceId(SURFACE), SlotId("bottom"), Modifier.fillMaxWidth())
        }
    }
}

private const val SURFACE = "appshell.rightrail"
