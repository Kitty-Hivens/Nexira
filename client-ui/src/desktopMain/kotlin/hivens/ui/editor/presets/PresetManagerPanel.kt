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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hivens.ui.theme.CelestiaTheme
import java.text.DateFormat
import java.util.Date

// Modal preset manager. Reached via the "Presets" chip on the
// edit-mode pill. Lists existing presets with Load / Delete / Export
// per row, plus a "Save current as..." text field at top.
@Composable
fun PresetManagerPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSaveCurrent: (String) -> Unit,
    onLoad: (PresetMeta) -> Unit,
    onDelete: (PresetMeta) -> Unit,
    onExport: (PresetMeta) -> Unit,
    listProvider: () -> List<PresetMeta>,
) {
    if (!visible) return

    var presets by remember(visible) { mutableStateOf(listProvider()) }
    var newName by remember(visible) { mutableStateOf("") }
    LaunchedEffect(visible) {
        if (visible) presets = listProvider()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color           = CelestiaTheme.colors.surface,
            shape           = RoundedCornerShape(16.dp),
            shadowElevation = 18.dp,
            modifier        = Modifier
                .width(520.dp)
                .height(620.dp)
                .shadow(elevation = 24.dp, shape = RoundedCornerShape(16.dp)),
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                // Header
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier              = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector        = Icons.Default.Inventory2,
                            contentDescription = null,
                            tint               = CelestiaTheme.colors.primary,
                            modifier           = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text       = "Пресеты",
                            style      = MaterialTheme.typography.titleMedium,
                            color      = CelestiaTheme.colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть",
                             tint = CelestiaTheme.colors.textSecondary)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "Снимок layout + темы + стиля. Сохрани сейчас, загрузи когда угодно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary,
                )

                Spacer(Modifier.height(16.dp))

                // Save row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CelestiaTheme.colors.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    BasicTextField(
                        value         = newName,
                        onValueChange = { newName = it },
                        singleLine    = true,
                        textStyle     = TextStyle(
                            color    = CelestiaTheme.colors.textPrimary,
                            fontSize = 14.sp,
                        ),
                        cursorBrush   = SolidColor(CelestiaTheme.colors.primary),
                        modifier      = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (newName.isEmpty()) {
                                Text(
                                    text  = "Имя пресета...",
                                    color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.55f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            inner()
                        },
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            val n = newName.trim()
                            if (n.isNotEmpty()) {
                                onSaveCurrent(n)
                                newName = ""
                                presets = listProvider()
                            }
                        },
                        shape   = RoundedCornerShape(8.dp),
                        enabled = newName.isNotBlank(),
                        colors  = ButtonDefaults.buttonColors(
                            containerColor = CelestiaTheme.colors.primary,
                            contentColor   = Color.White,
                        ),
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Сохранить", fontWeight = FontWeight.Medium)
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text       = "Сохранённые (${presets.size})",
                    style      = MaterialTheme.typography.labelMedium,
                    color      = CelestiaTheme.colors.textSecondary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))

                if (presets.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(CelestiaTheme.colors.surfaceVariant.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text  = "Пусто. Сохрани текущий layout как первый пресет.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CelestiaTheme.colors.textSecondary,
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
                                    onDelete(meta)
                                    presets = listProvider()
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CelestiaTheme.colors.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text       = meta.name,
                style      = MaterialTheme.typography.bodyLarge,
                color      = CelestiaTheme.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Text(
                text       = formatTime(meta.createdAt),
                style      = MaterialTheme.typography.labelSmall,
                color      = CelestiaTheme.colors.textSecondary.copy(alpha = 0.75f),
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onLoad,
            shape   = RoundedCornerShape(8.dp),
            colors  = ButtonDefaults.buttonColors(
                containerColor = CelestiaTheme.colors.primary,
                contentColor   = Color.White,
            ),
        ) {
            Text("Применить", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onExport, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector        = Icons.Default.Upload,
                contentDescription = "Экспорт",
                tint               = CelestiaTheme.colors.textSecondary,
                modifier           = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector        = Icons.Default.Delete,
                contentDescription = "Удалить",
                tint               = CelestiaTheme.colors.error,
                modifier           = Modifier.size(18.dp),
            )
        }
    }
}

private fun formatTime(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
}
