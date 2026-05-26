package hivens.ui.screens.library.content

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import hivens.core.api.dto.smrt.SmrtAssetEntry
import hivens.core.api.dto.smrt.SmrtSource
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme

/**
 * One asset row -- resourcepack / shaderpack / config / generic file
 * shipped alongside the mods. Same expand pattern as [ModRowPanel] but
 * lighter: assets carry less metadata, so the collapsed row shows
 * dest path + size + category + source-badge, and the expanded body
 * is just the description block.
 */
@Composable
fun AssetRowPanel(asset: SmrtAssetEntry, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    var expanded by remember(asset.dest) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(glassSurfaceAlpha(0.4f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier              = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector        = Icons.Default.Folder,
                contentDescription = null,
                tint               = CelestiaTheme.colors.primary.copy(alpha = 0.8f),
                modifier           = Modifier.size(20.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = asset.dest,
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = CelestiaTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text  = s.contentTabAssetSizeLabel(asset.sizeBytes / 1024L),
                        style = MaterialTheme.typography.labelSmall,
                        color = CelestiaTheme.colors.textSecondary,
                    )
                    asset.display?.category?.takeIf { it.isNotBlank() }?.let { cat ->
                        Text(
                            text  = "· $cat",
                            style = MaterialTheme.typography.labelSmall,
                            color = CelestiaTheme.colors.textSecondary,
                        )
                    }
                    if (!asset.required) {
                        Text(
                            text  = "· ${s.contentTabAssetOptional}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CelestiaTheme.colors.textSecondary,
                        )
                    }
                }
            }

            AssetSourceBadge(asset.source)

            Icon(
                imageVector        = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint               = CelestiaTheme.colors.textSecondary,
                modifier           = Modifier.size(20.dp),
            )
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            val description = asset.display?.description?.takeIf { it.isNotBlank() }
            if (description != null) {
                Box(modifier = Modifier.padding(start = 30.dp)) { Markdown(content = description) }
            } else {
                Text(
                    text     = s.contentTabAssetNoDescription,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = CelestiaTheme.colors.textSecondary,
                    modifier = Modifier.padding(start = 30.dp),
                )
            }
        }
    }
}

@Composable
private fun AssetSourceBadge(source: SmrtSource) {
    val (label, color) = when (source) {
        is SmrtSource.Modrinth   -> "Modrinth" to Color(0xFF22C55E)
        is SmrtSource.SmrtCache  -> "Mirror"   to Color(0xFF3B82F6)
        is SmrtSource.SmrtStatic -> "Static"   to Color(0xFF94A3B8)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
    }
}
