package hivens.ui.screens.library.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.api.dto.smrt.SmrtSource

// Provenance pill on a content row: where the entry came from
// (Modrinth / mirror cache / mirror-static). Colour is the source's
// brand-ish tint so the badge reads at a glance across mod and asset
// rows alike. Label is a fixed short tag, not localized.
@Composable
internal fun SourceBadge(source: SmrtSource) {
    val (label, color) = when (source) {
        is SmrtSource.Modrinth   -> "Modrinth" to Color(0xFF22C55E)
        is SmrtSource.SmrtCache  -> "Mirror"   to Color(0xFF3B82F6)
        is SmrtSource.SmrtStatic -> "Static"   to Color(0xFF94A3B8)
        is SmrtSource.Unknown    -> "Unknown"  to Color(0xFF6B7280)
    }
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
    }
}
