package hivens.ui.widgets.serverdetails

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.theme.CelestiaTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

// Two-chip tag row: minecraft version + assetDir identifier.
@Widget(id = "server.details.tagbar", displayName = "widget.server.details.tagbar")
@Composable
fun ServerDetailsTagBarWidget(instance: WidgetInstance) {
    val ctx = LocalServerDetailsContext.current
    Row(modifier = Modifier.padding(vertical = 16.dp)) {
        Tag(ctx.server.version)
        Spacer(Modifier.width(8.dp))
        Tag(ctx.server.assetDir)
    }
}

@Composable
private fun Tag(text: String) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(CelestiaTheme.colors.primary.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text       = text,
            style      = MaterialTheme.typography.bodySmall,
            color      = CelestiaTheme.colors.primary,
            fontWeight = FontWeight.Bold,
        )
    }
}
