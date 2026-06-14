package hivens.ui.widgets.serverdetails

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

// Long-form description text or a warning panel when the
// description.txt file is missing on disk. Self-scrolls inside a
// Column.verticalScroll so a wall of text still fits the slot --
// the surface does not wrap the slot itself in scroll.
@Widget(id = "server.details.description", displayName = "widget.server.details.description")
@Composable
fun ServerDetailsDescriptionWidget(instance: WidgetInstance) {
    val ctx = LocalServerDetailsContext.current
    val s = LocalStrings.current
    val description by ctx.description

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (description != null) {
            Text(
                text       = description!!,
                style      = MaterialTheme.typography.bodyLarge,
                color      = CelestiaTheme.colors.textPrimary.copy(alpha = 0.8f),
                lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.5,
            )
        } else {
            MissingDataWarning(
                title = s.serverDetailMissingTitle,
                body  = s.serverDetailMissingPath(ctx.assetsPath.absolutePath, "description.txt"),
                path  = ctx.assetsPath.absolutePath,
            )
        }
    }
}

@Composable
private fun MissingDataWarning(title: String, body: String, path: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Color(0xFFFFAA00).copy(alpha = 0.12f))
            .border(1.dp, Color(0xFFFFAA00).copy(alpha = 0.3f), MaterialTheme.shapes.medium)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Warning, null, tint = Color(0xFFFFAA00))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color(0xFFFFAA00), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.textPrimary.copy(alpha = 0.7f))
            Text(
                text       = path,
                style      = MaterialTheme.typography.bodySmall,
                color      = CelestiaTheme.colors.textSecondary.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
