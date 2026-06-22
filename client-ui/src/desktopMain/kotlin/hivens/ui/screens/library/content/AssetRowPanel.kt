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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import hivens.core.api.dto.smrt.SmrtAssetEntry
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme

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
            .clip(MaterialTheme.shapes.medium)
            .background(glassSurfaceAlpha(0.4f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier              = Modifier.fillMaxWidth(),
        ) {
            Symbol(icon = NxIcon.Folder,
                contentDescription = null,
                tint               = NxTheme.colors.primary.copy(alpha = 0.8f),
                modifier           = Modifier.size(20.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = asset.dest,
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = NxTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text  = s.contentTabAssetSizeLabel(asset.sizeBytes / 1024L),
                        style = MaterialTheme.typography.labelSmall,
                        color = NxTheme.colors.textSecondary,
                    )
                    asset.display?.category?.takeIf { it.isNotBlank() }?.let { cat ->
                        Text(
                            text  = "· $cat",
                            style = MaterialTheme.typography.labelSmall,
                            color = NxTheme.colors.textSecondary,
                        )
                    }
                    if (!asset.required) {
                        Text(
                            text  = "· ${s.contentTabAssetOptional}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NxTheme.colors.textSecondary,
                        )
                    }
                }
            }

            SourceBadge(asset.source)

            Symbol(icon = if (expanded) NxIcon.ExpandLess else NxIcon.ExpandMore,
                contentDescription = null,
                tint               = NxTheme.colors.textSecondary,
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
                    color    = NxTheme.colors.textSecondary,
                    modifier = Modifier.padding(start = 30.dp),
                )
            }
        }
    }
}
