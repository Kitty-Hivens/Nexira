package hivens.ui.screens.customization

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.components.GlassCard
import hivens.ui.customization.ColorRole
import hivens.ui.customization.CustomizationSettings
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.CardSurface
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle

/**
 * Experimental visual customization layer that sits on top of the
 * active theme + bg. Knobs here apply globally and persist via
 * [hivens.ui.customization.CustomizationManager].
 *
 * Default state is no-op (densityScale = 1, glassIntensity = 1, no
 * accent override, experimental colors disabled). A user who never
 * opens this screen sees no visual difference.
 *
 * Heavy knobs (corner radius scale, global animation speed) are
 * deliberately NOT here -- those require threading through every
 * shape / animation spec call site and are deferred to a follow-up.
 */
@Composable
fun CustomizationExtensionScreen(
    currentSettings: CustomizationSettings,
    onSettingsChanged: (CustomizationSettings) -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalStrings.current
    var settings by remember { mutableStateOf(currentSettings) }

    fun update(block: CustomizationSettings.() -> CustomizationSettings) {
        settings = settings.block()
        onSettingsChanged(settings)
    }

    PuppetScreen("CustomizationExtension")
    PuppetClick("customization.back") { onBack() }
    PuppetClick("customization.reset") {
        settings = CustomizationSettings(); onSettingsChanged(settings)
    }

    // Counter-wrap the screen back to the base (unscaled) density so
    // the density slider stays grabbable while every other surface
    // (sidebar, right panel, dropped-in dialogs) live-scales as the
    // user drags. Without this, every drag tick re-measures the
    // slider host under a new density and Material's gesture detector
    // loses the pointer.
    val outerDensity = LocalDensity.current
    val baseDensity  = remember(outerDensity, settings.densityScale) {
        Density(
            outerDensity.density / settings.densityScale.coerceAtLeast(0.01f),
            outerDensity.fontScale,
        )
    }
    CompositionLocalProvider(LocalDensity provides baseDensity) {

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = CelestiaTheme.colors.textPrimary)
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(s.customizationTitle, style = MaterialTheme.typography.headlineSmall,
                     fontWeight = FontWeight.Bold, color = CelestiaTheme.colors.textPrimary)
                Text(s.customizationSubtitle, style = MaterialTheme.typography.bodySmall,
                     color = CelestiaTheme.colors.textSecondary)
            }
        }

        Spacer(Modifier.height(20.dp))

        GlassCard(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SectionTitle(s.customizationSectionVisual)
                LabeledSlider(s.customizationDensity, settings.densityScale, 0.85f..1.15f, "%.2fx") {
                    update { copy(densityScale = it) }
                }
                // Brut renders cards as CardSurface.Flat which ignores
                // palette.glassAlpha entirely; hide the slider rather
                // than leave it silently inert.
                if (LocalStyle.current.cardSurface == CardSurface.Glass) {
                    LabeledSlider(s.customizationGlassIntensity, settings.glassIntensity, 0f..1f, "%.0f%%", 100f) {
                        update { copy(glassIntensity = it) }
                    }
                }

                HorizontalDivider(color = CelestiaTheme.colors.outline.copy(alpha = 0.15f))

                SectionTitle(s.customizationAccentOverride)
                HexField(
                    initialHex = settings.accentOverride ?: "",
                    invalidLabel = s.customizationHexInvalid,
                    onValidHex = { hex -> update { copy(accentOverride = hex) } },
                )
                if (settings.accentOverride != null) {
                    OutlinedButton(
                        onClick  = { update { copy(accentOverride = null) } },
                        shape    = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(s.customizationAccentClear) }
                }

                HorizontalDivider(color = CelestiaTheme.colors.outline.copy(alpha = 0.15f))

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.customizationExperimentalToggle, fontWeight = FontWeight.Bold,
                             color = CelestiaTheme.colors.textPrimary)
                        Text(s.customizationExperimentalSub, style = MaterialTheme.typography.bodySmall,
                             color = CelestiaTheme.colors.textSecondary)
                    }
                    Switch(
                        checked         = settings.experimentalColorOverridesEnabled,
                        onCheckedChange = { update { copy(experimentalColorOverridesEnabled = it) } },
                        colors          = SwitchDefaults.colors(
                            checkedThumbColor = CelestiaTheme.colors.primary,
                            checkedTrackColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f),
                        ),
                    )
                }

                if (settings.experimentalColorOverridesEnabled) {
                    SectionTitle(s.customizationSectionColors)
                    listOf(
                        ColorRole.PRIMARY,
                        ColorRole.SECONDARY,
                        ColorRole.BACKGROUND,
                        ColorRole.SURFACE,
                        ColorRole.SUCCESS,
                        ColorRole.ERROR,
                        ColorRole.OUTLINE,
                    ).forEach { role ->
                        ColorRoleRow(
                            role     = role,
                            currentHex = settings.colorOverrides[role],
                            invalidLabel = s.customizationHexInvalid,
                            onValidHex = { hex ->
                                update {
                                    val newMap = colorOverrides.toMutableMap().also { it[role] = hex }
                                    copy(colorOverrides = newMap)
                                }
                            },
                            onClear = {
                                update {
                                    val newMap = colorOverrides.toMutableMap().also { it.remove(role) }
                                    copy(colorOverrides = newMap)
                                }
                            },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick  = { settings = CustomizationSettings(); onSettingsChanged(settings) },
                    shape    = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s.customizationReset)
                }
            }
        }
    }
    } // end CompositionLocalProvider(LocalDensity)
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = CelestiaTheme.colors.primary,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    displayMultiplier: Float = 1f,
    onValueChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.textSecondary,
             modifier = Modifier.width(150.dp))
        Slider(
            value         = value,
            onValueChange = onValueChange,
            valueRange    = range,
            modifier      = Modifier.weight(1f),
            colors        = SliderDefaults.colors(
                thumbColor          = CelestiaTheme.colors.primary,
                activeTrackColor    = CelestiaTheme.colors.primary,
                inactiveTrackColor  = CelestiaTheme.colors.outline.copy(alpha = 0.2f),
            ),
        )
        Text(
            format.format(value * displayMultiplier),
            style    = MaterialTheme.typography.labelSmall,
            color    = CelestiaTheme.colors.textSecondary.copy(alpha = 0.6f),
            modifier = Modifier.width(54.dp),
        )
    }
}

@Composable
private fun ColorRoleRow(
    role: String,
    currentHex: String?,
    invalidLabel: String,
    onValidHex: (String) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            role.replaceFirstChar { it.uppercase() },
            modifier   = Modifier.width(100.dp),
            color      = CelestiaTheme.colors.textSecondary,
            style      = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        HexField(
            initialHex = currentHex ?: "",
            invalidLabel = invalidLabel,
            onValidHex = onValidHex,
            modifier   = Modifier.weight(1f),
        )
        if (currentHex != null) {
            OutlinedButton(
                onClick        = onClear,
                shape          = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) { Text("x", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun HexField(
    initialHex: String,
    invalidLabel: String,
    onValidHex: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(initialHex) { mutableStateOf(initialHex) }
    val parsed = parseHexOrNull(text)
    val valid  = text.isBlank() || parsed != null

    Row(
        modifier              = modifier,
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(parsed ?: CelestiaTheme.colors.surface)
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp)),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(CelestiaTheme.colors.surface.copy(alpha = 0.4f))
                .border(
                    1.dp,
                    if (valid) CelestiaTheme.colors.outline.copy(alpha = 0.3f) else CelestiaTheme.colors.error,
                    RoundedCornerShape(6.dp),
                )
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value         = text,
                onValueChange = { t ->
                    text = t
                    if (t.isBlank()) {
                        // empty is "no override" -- handled by parent via onClear
                    } else {
                        parseHexOrNull(t)?.let { onValidHex(t) }
                    }
                },
                singleLine    = true,
                textStyle     = TextStyle(
                    color      = CelestiaTheme.colors.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 13.sp,
                ),
                cursorBrush   = androidx.compose.ui.graphics.SolidColor(CelestiaTheme.colors.primary),
                modifier      = Modifier.fillMaxWidth(),
            )
        }
        if (!valid) {
            Text(invalidLabel, color = CelestiaTheme.colors.error, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun parseHexOrNull(hex: String): Color? = try {
    val clean = hex.removePrefix("#").trim()
    if (clean.length != 6 && clean.length != 8) null
    else {
        val full = if (clean.length == 6) "FF$clean" else clean
        Color(full.toLong(16))
    }
} catch (_: Exception) { null }

@Suppress("unused")
private fun ImeAction.alwaysReferenced() = Unit
