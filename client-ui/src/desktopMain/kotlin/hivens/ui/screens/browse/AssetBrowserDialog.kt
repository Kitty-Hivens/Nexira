package hivens.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.api.dto.smrt.SmrtAssetEntry
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme

/**
 * Floating asset browser. Same backdrop / dismiss / footer shape as
 * [ModBrowserDialog]. Assets carry less rich metadata (no
 * incompatibilities in practice, descriptions rare), so rows stay
 * flat -- dest path + size chip + source chip + checkbox for
 * optional entries.
 *
 * Required assets render with a disabled-checked checkbox; optional
 * ones (e.g. a heavyweight resource pack) are user-toggleable.
 * Selection set returns via [onApply] for the parent to stash until
 * the install pipeline reads it.
 */
@Composable
fun AssetBrowserDialog(
    assets: List<SmrtAssetEntry>,
    initialSelection: Set<String>,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current

    var query     by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(initialSelection) }

    val filtered = remember(assets, query) {
        if (query.isBlank()) assets
        else {
            val q = query.trim().lowercase()
            assets.filter { it.dest.lowercase().contains(q) }
        }
    }

    val effectiveSelection = remember(selection, assets) {
        selection + assets.filter { it.required }.map { it.dest }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 600.dp, max = 1100.dp)
                .heightIn(min = 400.dp, max = 800.dp)
                .padding(40.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CelestiaTheme.colors.background)
                .pointerInput(Unit) { detectTapGestures(onTap = { /* swallow */ }) },
        ) {
            // Header
            Row(
                modifier              = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = s.assetBrowserTitle,
                        style      = MaterialTheme.typography.titleLarge,
                        color      = CelestiaTheme.colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text  = s.assetBrowserSelectedCount.format(effectiveSelection.size, assets.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = CelestiaTheme.colors.textSecondary,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = CelestiaTheme.colors.textPrimary)
                }
            }

            HorizontalDivider(color = CelestiaTheme.colors.outline.copy(alpha = 0.2f))

            // Search
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                OutlinedTextField(
                    value         = query,
                    onValueChange = { query = it },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    placeholder   = { Text(s.assetBrowserSearch, style = MaterialTheme.typography.bodySmall) },
                    leadingIcon   = {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp),
                            tint = CelestiaTheme.colors.textSecondary)
                    },
                    shape         = RoundedCornerShape(10.dp),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedTextColor          = CelestiaTheme.colors.textPrimary,
                        unfocusedTextColor        = CelestiaTheme.colors.textPrimary,
                        focusedBorderColor        = CelestiaTheme.colors.primary,
                        unfocusedBorderColor      = CelestiaTheme.colors.outline.copy(alpha = 0.4f),
                        cursorColor               = CelestiaTheme.colors.primary,
                        focusedPlaceholderColor   = CelestiaTheme.colors.textSecondary.copy(alpha = 0.7f),
                        unfocusedPlaceholderColor = CelestiaTheme.colors.textSecondary.copy(alpha = 0.7f),
                    ),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text  = s.modBrowserShowingCount.format(filtered.size, assets.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = CelestiaTheme.colors.textSecondary,
                )
            }

            HorizontalDivider(color = CelestiaTheme.colors.outline.copy(alpha = 0.2f))

            LazyColumn(
                modifier            = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            text     = s.assetBrowserEmptyFilter,
                            style    = MaterialTheme.typography.bodySmall,
                            color    = CelestiaTheme.colors.textSecondary.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                } else {
                    items(items = filtered, key = { it.dest }) { asset ->
                        SelectableAssetRow(
                            asset      = asset,
                            isSelected = asset.dest in effectiveSelection,
                            onToggle   = {
                                if (asset.required) return@SelectableAssetRow
                                selection = if (asset.dest in selection) {
                                    selection - asset.dest
                                } else {
                                    selection + asset.dest
                                }
                            },
                        )
                    }
                }
            }

            HorizontalDivider(color = CelestiaTheme.colors.outline.copy(alpha = 0.2f))

            Row(
                modifier              = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape   = RoundedCornerShape(10.dp),
                ) { Text(s.modBrowserCancel) }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { onApply(selection); onDismiss() },
                    shape   = RoundedCornerShape(10.dp),
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = CelestiaTheme.colors.primary,
                        contentColor   = Color.White,
                    ),
                ) { Text(s.modBrowserApply, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun SelectableAssetRow(
    asset: SmrtAssetEntry,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    val s = LocalStrings.current
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(glassSurfaceAlpha(0.35f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked         = isSelected,
            onCheckedChange = { onToggle() },
            enabled         = !asset.required,
            colors          = CheckboxDefaults.colors(
                checkedColor          = CelestiaTheme.colors.primary,
                uncheckedColor        = CelestiaTheme.colors.outline,
                disabledCheckedColor  = CelestiaTheme.colors.primary.copy(alpha = 0.5f),
            ),
        )
        Text(
            text     = asset.dest,
            style    = MaterialTheme.typography.bodySmall,
            color    = CelestiaTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        SmallChipPill(if (asset.required) s.modBrowserRequiredChip else s.browseDetailModsOptional)
        SmallChipPill(formatBytesAsset(asset.sizeBytes))
        SourceChipPill(
            label = when (asset.source.javaClass.simpleName) {
                "Modrinth"   -> s.browseDetailSourceModrinth
                "SmrtCache"  -> s.browseDetailSourceMirrorCache
                "SmrtStatic" -> s.browseDetailSourceMirrorStatic
                else         -> asset.source.javaClass.simpleName
            },
            color = when (asset.source.javaClass.simpleName) {
                "Modrinth"   -> Color(0xFF22C55E)
                "SmrtCache"  -> Color(0xFF3B82F6)
                "SmrtStatic" -> Color(0xFF8B5CF6)
                else         -> Color(0xFF6B7280)
            },
        )
    }
}

@Composable
private fun SmallChipPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(glassSurfaceAlpha(0.5f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = CelestiaTheme.colors.textSecondary)
    }
}

@Composable
private fun SourceChipPill(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatBytesAsset(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val kb = bytes / 1_024.0
    if (kb < 1_024) return "%.1f KB".format(java.util.Locale.ROOT, kb)
    val mb = kb / 1_024.0
    if (mb < 1_024) return "%.1f MB".format(java.util.Locale.ROOT, mb)
    val gb = mb / 1_024.0
    return "%.2f GB".format(java.util.Locale.ROOT, gb)
}
