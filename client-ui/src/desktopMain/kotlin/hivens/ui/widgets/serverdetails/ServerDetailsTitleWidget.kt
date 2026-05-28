package hivens.ui.widgets.serverdetails

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import hivens.ui.theme.CelestiaTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

// Big display headline. Reads server.title (display string) or
// falls back to server.name (technical id) when title is unset.
@Widget(id = "server.details.title", displayName = "Заголовок сервера")
@Composable
fun ServerDetailsTitleWidget(instance: WidgetInstance) {
    val ctx = LocalServerDetailsContext.current
    Text(
        text       = ctx.server.title ?: ctx.server.name,
        style      = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Black,
        color      = CelestiaTheme.colors.textPrimary,
    )
}
