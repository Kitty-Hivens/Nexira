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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme

/**
 * A settings/config section: an accent header label over a single opaque body
 * plane. The rows live inside one [NxSurface] (not a per-row alpha card each),
 * and the separator is that plane's own bevel -- never an orphan divider in the
 * column (Rule 0/D07). Because the plane is an [NxSurface] with [glass] off, it
 * keeps a body and a luminance hairline under any theme and with no wallpaper
 * (Rule 2/3): the section stays a distinct plane when the coat comes off.
 *
 * Sections separate from each other by the caller's outer gap (the island
 * model), so a page is `Column(spacedBy(gap)) { NxSection(...){}; NxSection(...){} }`.
 */
@Composable
fun NxSection(
    title: String,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    level: NxSurfaceLevel = NxSurfaceLevel.Floating,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text       = title,
            modifier   = titleModifier,
            style      = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color      = NxTheme.colors.primary,
        )
        Spacer(Modifier.height(8.dp))
        NxSurface(level = level, glass = false, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier            = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(spacing),
                content             = content,
            )
        }
    }
}
