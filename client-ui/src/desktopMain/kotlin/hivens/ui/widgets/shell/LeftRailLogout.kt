package hivens.ui.widgets.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.theme.CelestiaTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

// Self-gates on isAuthenticated -- renders nothing when the session
// is absent so the slot can list the widget unconditionally and the
// layout graph stays free of auth-state vocabulary.
@Widget(id = "appshell.leftrail.logout", displayName = "Logout")
@Composable
fun LeftRailLogout(instance: WidgetInstance) {
    val ctx = LocalLeftRailContext.current
    if (!ctx.isAuthenticated) return

    // Center in the rail (the slot's Column is start-aligned post-Phase G).
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        IconButton(onClick = ctx.onLogout, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector        = Icons.AutoMirrored.Filled.ExitToApp,
                contentDescription = null,
                tint               = CelestiaTheme.colors.error.copy(alpha = 0.75f),
                modifier           = Modifier.size(22.dp),
            )
        }
    }
}
