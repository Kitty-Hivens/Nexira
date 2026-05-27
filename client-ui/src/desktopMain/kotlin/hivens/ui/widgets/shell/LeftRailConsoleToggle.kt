package hivens.ui.widgets.shell

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.theme.CelestiaTheme
import hivens.ui.utils.GameConsoleService
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import org.koin.compose.koinInject

@Widget(id = "appshell.leftrail.consoletoggle", displayName = "Console toggle")
@Composable
fun LeftRailConsoleToggle(instance: WidgetInstance) {
    val gameConsole: GameConsoleService = koinInject()
    IconButton(
        onClick  = {
            if (gameConsole.shouldShowConsole) gameConsole.hide()
            else gameConsole.show()
        },
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            imageVector        = Icons.Default.Build,
            contentDescription = null,
            tint               = if (gameConsole.shouldShowConsole)
                CelestiaTheme.colors.primary
            else
                CelestiaTheme.colors.textSecondary.copy(alpha = 0.55f),
            modifier           = Modifier.size(22.dp),
        )
    }
}
