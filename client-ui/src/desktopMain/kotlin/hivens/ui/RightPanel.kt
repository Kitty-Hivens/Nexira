package hivens.ui

import androidx.compose.foundation.background
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
 * the dividers between slots; widget content (auth panel, news feed,
 * console) resolves through SlotRenderer against the layout graph.
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
            SlotRenderer(SurfaceId(SURFACE), SlotId("auth"), Modifier.fillMaxWidth())
            HorizontalDivider(color = glassSurfaceAlpha(0.7f))
            // News fills the middle; console sits at the bottom and grows
            // with its own intrinsic height (badge collapsed -> tiny, panel
            // expanded -> ~400 dp). News loses height while console is
            // expanded, which is the right priority for the Atelier-style
            // "diagnostic overlay" -- when the user opens the console,
            // news takes a back seat.
            SlotRenderer(SurfaceId(SURFACE), SlotId("news"), Modifier.weight(1f).fillMaxWidth())
            HorizontalDivider(color = glassSurfaceAlpha(0.7f))
            SlotRenderer(SurfaceId(SURFACE), SlotId("console"), Modifier.fillMaxWidth())
        }
    }
}

private const val SURFACE = "appshell.rightrail"
