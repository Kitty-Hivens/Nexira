package hivens.ui.widgets.serverdetails

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxCalloutBanner
import hivens.ui.nx.NxCalloutTone
import hivens.ui.theme.LocalMonoFamily
import hivens.ui.theme.NxTheme
import hivens.widget.model.Widget

// Long-form description text or a warning callout when the
// description.txt file is missing on disk. Self-scrolls inside a
// Column.verticalScroll so a wall of text still fits the slot --
// the surface does not wrap the slot itself in scroll.
@Widget(id = "server.details.description", displayName = "widget.server.details.description")
@Composable
fun ServerDetailsDescriptionWidget() {
    val ctx = LocalServerDetailsContext.current
    val s = LocalStrings.current
    val description by ctx.description

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (description != null) {
            Text(
                text       = description!!,
                style      = MaterialTheme.typography.bodyLarge,
                color      = NxTheme.colors.textPrimary.copy(alpha = 0.8f),
                lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.5,
            )
        } else {
            NxCalloutBanner(
                tone  = NxCalloutTone.Warning,
                title = s.serverDetailMissingTitle,
                body  = s.serverDetailMissingPath("description.txt"),
            ) {
                Text(
                    text       = ctx.assetsPath.absolutePath,
                    style      = MaterialTheme.typography.bodySmall,
                    color      = NxTheme.colors.textSecondary.copy(alpha = 0.6f),
                    fontFamily = LocalMonoFamily.current,
                )
            }
        }
    }
}
