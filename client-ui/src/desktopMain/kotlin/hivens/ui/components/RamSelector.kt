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
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme

/**
 * Precise RAM selector with preset buttons and manual input.
 * Replaces the imprecise slider.
 */
@Composable
fun RamSelector(
    currentMb: Int,
    onValueChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current

    val systemRamMb = remember {
        val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
        try {
            val method = osBean.javaClass.getMethod("getTotalPhysicalMemorySize")
            method.isAccessible = true
            ((method.invoke(osBean) as Long) / (1024 * 1024)).toInt()
        } catch (_: Exception) { 16384 }
    }

    val allPresets = listOf(1024, 2048, 3072, 4096, 6144, 8192, 12288, 16384)
    val presets = remember(systemRamMb) {
        allPresets.filter { it <= (systemRamMb * 0.75).toInt() }.ifEmpty { listOf(1024, 2048) }
    }

    var customInput by remember { mutableStateOf("") }
    var isCustomMode by remember { mutableStateOf(!allPresets.contains(currentMb)) }
    val focusManager = LocalFocusManager.current

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(s.serverSettingsRam, style = MaterialTheme.typography.bodyMedium, color = CelestiaTheme.colors.textPrimary)
            Text(formatRam(currentMb), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = CelestiaTheme.colors.primary)
        }

        Spacer(Modifier.height(10.dp))

        // Preset buttons
        val chunked = presets.chunked(4)
        chunked.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { preset ->
                    val isSelected = currentMb == preset && !isCustomMode
                    val bgColor by animateColorAsState(
                        if (isSelected) CelestiaTheme.colors.primary else CelestiaTheme.colors.surface.copy(alpha = 0.5f),
                        tween(200)
                    )
                    val textColor by animateColorAsState(
                        if (isSelected) Color.White else CelestiaTheme.colors.textSecondary,
                        tween(200)
                    )
                    Box(
                        modifier = Modifier.weight(1f).height(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, if (isSelected) CelestiaTheme.colors.primary.copy(alpha = 0.7f) else CelestiaTheme.colors.outline.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .background(bgColor)
                            .clickable { isCustomMode = false; onValueChanged(preset) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(formatRam(preset), fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = textColor, textAlign = TextAlign.Center)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(8.dp))

        // Custom input
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.ramCustomInputLabel, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.textSecondary, modifier = Modifier.width(100.dp))
            OutlinedTextField(
                value = if (isCustomMode) customInput else "",
                onValueChange = { input ->
                    customInput = input.filter { it.isDigit() }
                    isCustomMode = true
                    customInput.toIntOrNull()?.takeIf { it in 512..32768 }?.let(onValueChanged)
                },
                // No fixed height — Material3 OutlinedTextField needs ~56 dp to lay out
                // its placeholder; forcing 48 dp clipped it past the bottom border so
                // the digit visually "fell through" the field.
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (isCustomMode) "" else currentMb.toString(), color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.4f), fontSize = 13.sp) },
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
                shape = RoundedCornerShape(8.dp)
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

private fun formatRam(mb: Int): String = when {
    mb >= 1024 && mb % 1024 == 0 -> "${mb / 1024} GB"
    mb >= 1024 -> "%.1f GB".format(mb / 1024.0)
    else -> "$mb MB"
}
