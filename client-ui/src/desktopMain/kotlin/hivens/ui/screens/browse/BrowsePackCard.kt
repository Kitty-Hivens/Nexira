package hivens.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.core.api.dto.smrt.SmrtPackSummary

/**
 * Tile shape for a single [SmrtPackSummary] from the mirror catalog.
 * Banner-as-background with Mirror gradient (every catalog entry is
 * mirror-sourced today; future Modrinth / CurseForge integrations
 * would need a per-source colour pass).
 *
 * Click is intentionally a no-op for now -- standalone install + the
 * BrowsePackDetail navigation hop lives in the next PR.
 */
@Composable
fun BrowsePackCard(pack: SmrtPackSummary) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(mirrorGradient()),
    ) {
        // Darken overlay for text readability over the gradient.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))

        Column(
            modifier            = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.Top,
            ) {
                Column(modifier = Modifier.padding(end = 8.dp)) {
                    Text(
                        text       = pack.displayName,
                        style      = MaterialTheme.typography.titleMedium,
                        color      = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                    )
                    if (pack.tagline.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text     = pack.tagline,
                            style    = MaterialTheme.typography.bodySmall,
                            color    = Color.White.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                SourceBadgeMirror(featured = pack.featured)
            }

            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MetaChip(text = "MC ${pack.minecraftVersion}")
                MetaChip(text = pack.latestPackVersion)
                if (pack.tags.isNotEmpty()) {
                    pack.tags.take(2).forEach { MetaChip(text = it, emphasis = false) }
                }
            }
        }
    }
}

@Composable
private fun SourceBadgeMirror(featured: Boolean) {
    val label = if (featured) "Mirror ★" else "Mirror"
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF3B82F6).copy(alpha = 0.85f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text       = label,
            color      = Color.White,
            fontSize   = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MetaChip(text: String, emphasis: Boolean = true) {
    val bg = if (emphasis) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.10f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text       = text,
            color      = Color.White.copy(alpha = if (emphasis) 0.95f else 0.75f),
            fontSize   = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
        )
    }
}

private fun mirrorGradient(): Brush = Brush.linearGradient(
    listOf(Color(0xFF1E3A8A), Color(0xFF1D4ED8)),
)
