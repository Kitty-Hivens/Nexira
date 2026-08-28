package hivens.ui.widgets.serverdetails

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import hivens.ui.theme.NxTheme
import hivens.widget.model.Widget

// Big display headline. Reads server.title (display string) or
// falls back to server.name (technical id) when title is unset.
@Widget(id = "server.details.title", displayName = "widget.server.details.title")
@Composable
fun ServerDetailsTitleWidget() {
    val ctx = LocalServerDetailsContext.current
    Text(
        text       = ctx.server.title ?: ctx.server.name,
        style      = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Black,
        color      = NxTheme.colors.textPrimary,
    )
}
