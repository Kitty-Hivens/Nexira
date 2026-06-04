package hivens.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.core.jvm.SystemMemory
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme

/**
 * RAM mode selector. Represents a MODE, not just a number:
 *  - [isAuto] true  -> "Auto": the machine-aware Automatic baseline, refined by the
 *    adaptive sizer when it is on. The chip shows [resolvedAutoMb] -- the heap the next
 *    launch will actually use.
 *  - [isAuto] false -> a pinned (Fixed) value: [currentMb], from a preset or typed.
 *
 * Stateless: the host owns the mode. Picking a preset/custom calls [onValueChanged]
 * (pins the instance); the Auto chip calls [onAutoSelected] (un-pins it).
 */
@Composable
fun RamSelector(
    isAuto: Boolean,
    resolvedAutoMb: Int,
    currentMb: Int,
    onAutoSelected: () -> Unit,
    onValueChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current

    val systemRamMb = remember { SystemMemory.totalPhysicalMb() }

    val allPresets = listOf(1024, 2048, 3072, 4096, 6144, 8192, 12288, 16384)
    val presets = remember(systemRamMb) {
        allPresets.filter { it <= (systemRamMb * 0.75).toInt() }.ifEmpty { listOf(1024, 2048) }
    }

    // Seed the custom field from a pinned non-preset value (against the machine-FILTERED
    // presets, so a value pinned above the machine ceiling still shows in the field, not
    // a missing chip). Keyed on the inputs so a profile load / instance switch re-seeds.
    var customInput by remember(isAuto, currentMb) {
        mutableStateOf(if (!isAuto && currentMb in 512..32768 && !presets.contains(currentMb)) currentMb.toString() else "")
    }
    var isCustomMode by remember(isAuto, currentMb) { mutableStateOf(!isAuto && !presets.contains(currentMb)) }
    val focusManager = LocalFocusManager.current

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(s.serverSettingsRam, style = MaterialTheme.typography.bodyMedium, color = CelestiaTheme.colors.textPrimary)
            Text(
                formatRam(if (isAuto) resolvedAutoMb else currentMb),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = CelestiaTheme.colors.primary
            )
        }

        Spacer(Modifier.height(10.dp))

        // Auto mode: full-width chip above the presets. Default for an unpinned instance;
        // shows the heap Auto resolves to right now (Automatic baseline or adaptive-derived).
        RamChip(
            selected = isAuto,
            label = s.ramAutoLabel(formatRam(resolvedAutoMb)),
            modifier = Modifier.fillMaxWidth(),
            onClick = { isCustomMode = false; onAutoSelected() },
        )

        Spacer(Modifier.height(6.dp))

        // Preset buttons -- picking one pins the instance (Fixed).
        presets.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { preset ->
                    RamChip(
                        selected = !isAuto && !isCustomMode && currentMb == preset,
                        label = formatRam(preset),
                        modifier = Modifier.weight(1f),
                        onClick = { isCustomMode = false; onValueChanged(preset) },
                    )
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(8.dp))

        // Custom input -- typing a value also pins the instance.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.ramCustomInputLabel, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.textSecondary, modifier = Modifier.width(100.dp))
            OutlinedTextField(
                value = if (isCustomMode) customInput else "",
                onValueChange = { input ->
                    customInput = input.filter { it.isDigit() }
                    isCustomMode = true
                    customInput.toIntOrNull()?.takeIf { it in 512..32768 }?.let(onValueChanged)
                },
                // No fixed height -- Material3 OutlinedTextField needs ~56 dp to lay out
                // its placeholder; forcing 48 dp clipped it past the bottom border.
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (isCustomMode) "" else formatRam(if (isAuto) resolvedAutoMb else currentMb),
                        color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.4f),
                        fontSize = 13.sp,
                    )
                },
                suffix = { Text("MB", color = CelestiaTheme.colors.textSecondary, fontSize = 12.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = CelestiaTheme.colors.textPrimary, unfocusedTextColor = CelestiaTheme.colors.textPrimary,
                    cursorColor = CelestiaTheme.colors.primary, focusedBorderColor = CelestiaTheme.colors.primary,
                    unfocusedBorderColor = CelestiaTheme.colors.textSecondary.copy(alpha = 0.2f),
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent
                ),
                shape = MaterialTheme.shapes.small
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = s.ramSystemHint(formatRam(systemRamMb), formatRam((systemRamMb * 0.75).toInt())),
            style = MaterialTheme.typography.labelSmall,
            color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.5f)
        )
    }
}

/** A pill used for both the Auto chip and each preset; [selected] drives the fill/text. */
@Composable
private fun RamChip(selected: Boolean, label: String, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) CelestiaTheme.colors.primary else glassSurfaceAlpha(0.5f),
        tween(200),
    )
    val fg by animateColorAsState(
        if (selected) Color.White else CelestiaTheme.colors.textSecondary,
        tween(200),
    )
    Box(
        modifier = modifier.height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(
                1.dp,
                if (selected) CelestiaTheme.colors.primary.copy(alpha = 0.7f) else CelestiaTheme.colors.outline.copy(alpha = 0.2f),
                RoundedCornerShape(8.dp),
            )
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = fg, textAlign = TextAlign.Center)
    }
}

private fun formatRam(mb: Int): String = when {
    mb >= 1024 && mb % 1024 == 0 -> "${mb / 1024} GB"
    mb >= 1024 -> "%.1f GB".format(mb / 1024.0)
    else -> "$mb MB"
}
