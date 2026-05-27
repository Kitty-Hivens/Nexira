package hivens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import hivens.core.data.SessionData
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.theme.CelestiaTheme
import hivens.ui.widgets.shell.LocalRightRailContext
import hivens.ui.widgets.shell.RightRailContext
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId

/**
 * Right-side panel. Surface composable: owns the column container and
 * the divider between auth + news slots; widget content (auth panel,
 * news feed) resolves through SlotRenderer against the layout graph.
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
        Column(modifier = modifier.background(CelestiaTheme.colors.background)) {
            Box(Modifier.fillMaxWidth()) {
                SlotRenderer(SurfaceId(SURFACE), SlotId("auth"))
            }
            HorizontalDivider(color = glassSurfaceAlpha(0.7f))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SlotRenderer(SurfaceId(SURFACE), SlotId("news"))
            }
        }
    }
}

private const val SURFACE = "appshell.rightrail"
