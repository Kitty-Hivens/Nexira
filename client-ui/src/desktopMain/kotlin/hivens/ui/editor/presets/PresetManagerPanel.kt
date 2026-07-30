package hivens.ui.editor.presets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxButton
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalMonoFamily
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Modal preset manager. Reached via the "Presets" chip on the
// edit-mode pill. Lists existing presets with Load / Delete / Export
// per row, plus a "Save current as..." text field at top.
@Composable
fun PresetManagerPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSaveCurrent: suspend (String) -> Unit,
    onLoad: (PresetMeta) -> Unit,
    onDelete: suspend (PresetMeta) -> Unit,
    onExport: (PresetMeta) -> Unit,
    listProvider: () -> List<PresetMeta>,
) {
    if (!visible) return

    val s = LocalStrings.current
    val style = LocalStyle.current
    val scope = rememberCoroutineScope()
    var presets by remember(visible) { mutableStateOf(emptyList<PresetMeta>()) }
    var newName by remember(visible) { mutableStateOf("") }
    // Init empty + load off the UI thread: listProvider() is a directory scan and
    // ran twice before (once here during composition, once in the effect).
    LaunchedEffect(Unit) { presets = withContext(Dispatchers.IO) { listProvider() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color           = NxTheme.colors.surface,
            shape           = MaterialTheme.shapes.large,
            shadowElevation = style.panelElevation,
            modifier        = Modifier
                .width(520.dp)
                .height(620.dp),
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                // Header
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier              = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Symbol(icon = NxIcon.Inventory2,
                            contentDescription = null,
                            tint               = NxTheme.colors.primary,
                            modifier           = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text       = s.editorPresetsTitle,
                            style      = MaterialTheme.typography.titleMedium,
                            color      = NxTheme.colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Symbol(NxIcon.Close, contentDescription = s.editorClose,
                             tint = NxTheme.colors.textSecondary)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = s.editorPresetsIntro,
                    style = MaterialTheme.typography.bodySmall,
                    color = NxTheme.colors.textSecondary,
                )

                Spacer(Modifier.height(16.dp))

                // Save row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(NxTheme.colors.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    BasicTextField(
                        value         = newName,
                        onValueChange = { newName = it },
                        singleLine    = true,
                        textStyle     = TextStyle(
                            color    = NxTheme.colors.textPrimary,
                            fontSize = 14.sp,
                        ),
                        cursorBrush   = SolidColor(NxTheme.colors.primary),
                        modifier      = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (newName.isEmpty()) {
                                Text(
                                    text  = s.editorPresetNamePlaceholder,
                                    color = NxTheme.colors.textSecondary.copy(alpha = 0.55f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            inner()
                        },
                    )
                    Spacer(Modifier.width(10.dp))
                    NxButton(
                        label   = s.editorSave,
                        onClick = {
                            val n = newName.trim()
                            if (n.isNotEmpty()) {
                                scope.launch {
                                    onSaveCurrent(n)
                                    newName = ""
                                    // Reload AFTER the write lands (and off the UI
                                    // thread): listing before the suspend save
                                    // completed showed a stale set missing the
                                    // just-saved preset.
                                    presets = withContext(Dispatchers.IO) { listProvider() }
                                }
                            }
                        },
                        icon    = NxIcon.Save,
                        enabled = newName.isNotBlank(),
                        compact = true,
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text       = s.editorPresetsSaved(presets.size),
                    style      = MaterialTheme.typography.labelMedium,
                    color      = NxTheme.colors.textSecondary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))

                if (presets.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(NxTheme.colors.surfaceVariant.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text  = s.editorPresetsEmpty,
                            style = MaterialTheme.typography.bodySmall,
                            color = NxTheme.colors.textSecondary,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier            = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(items = presets, key = { it.name }) { meta ->
                            PresetRow(
                                meta     = meta,
                                onLoad   = { onLoad(meta) },
                                onDelete = {
                                    scope.launch {
                                        onDelete(meta)
                                        presets = withContext(Dispatchers.IO) { listProvider() }
                                    }
                                },
                                onExport = { onExport(meta) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetRow(
    meta: PresetMeta,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    val s = LocalStrings.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(NxTheme.colors.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text       = meta.name,
                style      = MaterialTheme.typography.bodyLarge,
                color      = NxTheme.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Text(
                text       = formatTime(meta.createdAt),
                style      = MaterialTheme.typography.labelSmall,
                color      = NxTheme.colors.textSecondary.copy(alpha = 0.75f),
                fontFamily = LocalMonoFamily.current,
            )
        }
        Spacer(Modifier.width(8.dp))
        NxButton(label = s.editorApply, onClick = onLoad, compact = true)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onExport, modifier = Modifier.size(36.dp)) {
            Symbol(icon = NxIcon.Upload,
                contentDescription = s.editorExport,
                tint               = NxTheme.colors.textSecondary,
                modifier           = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Symbol(icon = NxIcon.Delete,
                contentDescription = s.editorDelete,
                tint               = NxTheme.colors.error,
                modifier           = Modifier.size(18.dp),
            )
        }
    }
}

private fun formatTime(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
}
