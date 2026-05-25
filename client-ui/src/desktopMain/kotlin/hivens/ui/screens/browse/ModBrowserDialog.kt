package hivens.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.platform.SystemActions
import hivens.ui.theme.CelestiaTheme

/**
 * Floating mod browser. Renders as a centered card over a dimmed
 * backdrop within the launcher window (NOT a separate OS window --
 * desktop users do not expect a popup native window for a selection
 * dialog).
 *
 * Required mods render with a disabled, always-checked checkbox so
 * the user sees the full pack composition but cannot accidentally
 * un-include them. Optional mods are toggleable; incompatibility
 * warnings appear inline beneath each mod once another selected mod
 * declares it as incompatible.
 *
 * Selection state is local until the user hits Apply, at which
 * point the new Set<filename of selected optional mods> goes back
 * via [onApply]. Parent stores it ephemerally for now -- the install
 * pipeline (next PR) maps it to PackInstance.optionalContent.
 */
@Composable
fun ModBrowserDialog(
    mods: List<SmrtModEntry>,
    initialSelection: Set<String>,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current

    var query             by remember { mutableStateOf("") }
    var onlyOptional      by remember { mutableStateOf(false) }
    var selection         by remember { mutableStateOf(initialSelection) }
    val expanded          = remember { mutableStateOf(setOf<String>()) }

    val filtered = remember(mods, query, onlyOptional) {
        val q = query.trim().lowercase()
        mods.filter { mod ->
            val matchesQuery = q.isBlank()
                || mod.filename.lowercase().contains(q)
                || (mod.display?.name?.lowercase()?.contains(q) == true)
                || (mod.display?.category?.lowercase()?.contains(q) == true)
            val matchesFilter = !onlyOptional || !mod.required
            matchesQuery && matchesFilter
        }
    }

    // Currently-effective selection: all required mods plus the
    // user's optional picks. Used to drive the incompatibility
    // warnings (a warning fires when a *currently active* mod
    // declares the row as incompatible).
    val effectiveSelection = remember(selection, mods) {
        selection + mods.filter { it.required }.map { it.filename }
    }

    // Backdrop -- intercepts clicks so the launcher behind cannot be
    // clicked through, and dismisses the dialog on outside-tap.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        contentAlignment = Alignment.Center,
    ) {
        // Card with its own pointerInput consuming taps so they
        // don't bubble up to the backdrop dismiss handler.
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
                        text       = s.modBrowserTitle,
                        style      = MaterialTheme.typography.titleLarge,
                        color      = CelestiaTheme.colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text  = s.modBrowserSelectedCount.format(effectiveSelection.size, mods.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = CelestiaTheme.colors.textSecondary,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = CelestiaTheme.colors.textPrimary)
                }
            }

            HorizontalDivider(color = CelestiaTheme.colors.outline.copy(alpha = 0.2f))

            // Search + filter
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                OutlinedTextField(
                    value         = query,
                    onValueChange = { query = it },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    placeholder   = { Text(s.browseDetailModsSearch, style = MaterialTheme.typography.bodySmall) },
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked         = onlyOptional,
                        onCheckedChange = { onlyOptional = it },
                        colors          = SwitchDefaults.colors(
                            checkedThumbColor = CelestiaTheme.colors.primary,
                            checkedTrackColor = CelestiaTheme.colors.primary.copy(alpha = 0.4f),
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text  = s.modBrowserOnlyOptional,
                        style = MaterialTheme.typography.bodySmall,
                        color = CelestiaTheme.colors.textPrimary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text  = s.modBrowserShowingCount.format(filtered.size, mods.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = CelestiaTheme.colors.textSecondary,
                    )
                }
            }

            HorizontalDivider(color = CelestiaTheme.colors.outline.copy(alpha = 0.2f))

            // Scrollable mod list
            LazyColumn(
                modifier            = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            text     = s.browseDetailModsEmptyFilter,
                            style    = MaterialTheme.typography.bodySmall,
                            color    = CelestiaTheme.colors.textSecondary.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                } else {
                    items(items = filtered, key = { it.filename }) { mod ->
                        SelectableModRow(
                            mod              = mod,
                            isSelected       = mod.filename in effectiveSelection,
                            conflictsWith    = mod.findConflictsAmong(effectiveSelection),
                            isExpanded       = mod.filename in expanded.value,
                            onToggleSelect   = {
                                if (mod.required) return@SelectableModRow
                                selection = if (mod.filename in selection) {
                                    selection - mod.filename
                                } else {
                                    selection + mod.filename
                                }
                            },
                            onToggleExpand   = {
                                expanded.value = if (mod.filename in expanded.value) {
                                    expanded.value - mod.filename
                                } else {
                                    expanded.value + mod.filename
                                }
                            },
                        )
                    }
                }
            }

            HorizontalDivider(color = CelestiaTheme.colors.outline.copy(alpha = 0.2f))

            // Footer
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
private fun SelectableModRow(
    mod: SmrtModEntry,
    isSelected: Boolean,
    conflictsWith: List<String>,
    isExpanded: Boolean,
    onToggleSelect: () -> Unit,
    onToggleExpand: () -> Unit,
) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(glassSurfaceAlpha(0.35f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked         = isSelected,
                onCheckedChange = { onToggleSelect() },
                enabled         = !mod.required,
                colors          = CheckboxDefaults.colors(
                    checkedColor          = CelestiaTheme.colors.primary,
                    uncheckedColor        = CelestiaTheme.colors.outline,
                    disabledCheckedColor  = CelestiaTheme.colors.primary.copy(alpha = 0.5f),
                ),
            )
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onToggleExpand),
            ) {
                Text(
                    text       = mod.display?.name?.takeIf { it.isNotBlank() } ?: mod.filename,
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = CelestiaTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                )
                if (mod.display?.name?.takeIf { it.isNotBlank() } != null) {
                    Text(
                        text     = mod.filename,
                        style    = MaterialTheme.typography.labelSmall,
                        color    = CelestiaTheme.colors.textSecondary.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.size(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallChip(if (mod.required) s.modBrowserRequiredChip else s.browseDetailModsOptional)
                    SmallChip(formatBytesLocal(mod.sizeBytes))
                    SourceChipLocal(sourceLabel(mod.source.javaClass.simpleName, s), sourceColor(mod.source.javaClass.simpleName))
                    mod.display?.category?.takeIf { it.isNotBlank() }?.let { SmallChip(it) }
                }
            }
            IconButton(onClick = onToggleExpand) {
                Icon(
                    imageVector        = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint               = CelestiaTheme.colors.textSecondary,
                )
            }
        }

        if (conflictsWith.isNotEmpty()) {
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector        = Icons.Default.Warning,
                    contentDescription = null,
                    tint               = CelestiaTheme.colors.error,
                    modifier           = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text  = s.modBrowserConflictWarn.format(conflictsWith.joinToString(", ")),
                    style = MaterialTheme.typography.labelSmall,
                    color = CelestiaTheme.colors.error,
                )
            }
        }

        if (isExpanded) {
            Spacer(Modifier.size(8.dp))
            mod.display?.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text  = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textPrimary.copy(alpha = 0.9f),
                )
                Spacer(Modifier.size(6.dp))
            }
            mod.display?.license?.takeIf { it.isNotBlank() }?.let {
                SmallChip(it)
                Spacer(Modifier.size(6.dp))
            }
            mod.display?.url?.takeIf { it.isNotBlank() }?.let { url ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier          = Modifier
                        .clickable { SystemActions.openUrl(url) }
                        .padding(vertical = 4.dp),
                ) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint               = CelestiaTheme.colors.primary,
                        modifier           = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text  = url,
                        style = MaterialTheme.typography.labelSmall,
                        color = CelestiaTheme.colors.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallChip(text: String) {
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
private fun SourceChipLocal(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

private fun SmrtModEntry.findConflictsAmong(active: Set<String>): List<String> {
    // The row's own incompatible_with directly. Plus inverse: another
    // ACTIVE mod listing this row's filename as incompatible. Both
    // surfaces are worth warning about so the user understands the
    // full picture.
    val outgoing = display?.incompatibleWith.orEmpty().filter { it in active }
    return outgoing.distinct()
}

private fun sourceLabel(simpleName: String, s: hivens.ui.i18n.AppStrings): String = when (simpleName) {
    "Modrinth"   -> s.browseDetailSourceModrinth
    "SmrtCache"  -> s.browseDetailSourceMirrorCache
    "SmrtStatic" -> s.browseDetailSourceMirrorStatic
    else         -> simpleName
}

private fun sourceColor(simpleName: String): Color = when (simpleName) {
    "Modrinth"   -> Color(0xFF22C55E)
    "SmrtCache"  -> Color(0xFF3B82F6)
    "SmrtStatic" -> Color(0xFF8B5CF6)
    else         -> Color(0xFF6B7280)
}

private fun formatBytesLocal(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val kb = bytes / 1_024.0
    if (kb < 1_024) return "%.1f KB".format(java.util.Locale.ROOT, kb)
    val mb = kb / 1_024.0
    if (mb < 1_024) return "%.1f MB".format(java.util.Locale.ROOT, mb)
    val gb = mb / 1_024.0
    return "%.2f GB".format(java.util.Locale.ROOT, gb)
}
