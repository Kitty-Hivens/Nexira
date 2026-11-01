package hivens.ui.nx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.surface.NxSurface
import hivens.ui.theme.Spacing
import hivens.ui.theme.NxColor
import hivens.ui.surface.SurfaceKind

/**
 * A settings/config section: a header in the theme's lead colour over one
 * [SurfaceKind.Panel]. The rows live inside that one surface (not a per-row card
 * each), and the separator is the panel's own bevel, never an orphan divider in the
 * column (Rule 0/D07). A panel is a body, so the section stays a distinct plane
 * under any theme and with no wallpaper (Rule 2/3).
 *
 * Sections separate from each other by the caller's outer gap (the island
 * model), so a page is `Column(spacedBy(gap)) { NxSection(...){}; NxSection(...){} }`.
 */
@Composable
fun NxSection(
    title: String,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text       = title,
            modifier   = titleModifier,
            style      = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color      = NxColor.lead(text = true),
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.s8))
        NxSurface(SurfaceKind.Panel, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier            = Modifier.fillMaxWidth().padding(Spacing.s16),
                verticalArrangement = Arrangement.spacedBy(spacing),
                content             = content,
            )
        }
    }
}
