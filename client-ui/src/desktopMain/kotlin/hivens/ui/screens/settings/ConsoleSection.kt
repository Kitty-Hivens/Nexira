package hivens.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.launcher.platform.PlatformPaths
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxSwitch
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.CustomTheme
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.nexiraBrailleFamily
import hivens.ui.utils.ConsoleSettings
import hivens.ui.utils.ConsoleSettingsManager
import hivens.ui.utils.FilterRule
import hivens.ui.utils.HighlightRule
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Settings > Console. Owns its own [ConsoleSettingsManager] over the same
 * `console.json` the live console uses; loads fresh on open and writes on each
 * change, so there is no clobbering between this and the in-window gear. Edits
 * land in a running console when it is reopened.
 *
 * This slice carries Display (font / wrap / gutter / timestamps / buffer) and
 * severity Colours. Highlight + filter rules and the empty-state art manager
 * are added on top of the same [settings] state in follow-up slices.
 */
@Composable
internal fun ConsoleSection(paths: PlatformPaths) {
    val s = LocalStrings.current
    val json = remember { Json { ignoreUnknownKeys = true; encodeDefaults = true } }
    val manager = remember { ConsoleSettingsManager(paths.dataDir, json) }
    var settings by remember { mutableStateOf(manager.load()) }
    var artDraft by remember { mutableStateOf("") }
    fun update(next: ConsoleSettings) {
        val coerced = next.coerced()
        settings = coerced
        manager.save(coerced)
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSectionTitle(s.consoleSecDisplay)
        SliderRow(
            title      = s.consoleSecFontSize,
            valueLabel = "${settings.fontSize} sp",
            value      = settings.fontSize.toFloat(),
            range      = ConsoleSettings.MIN_FONT_SIZE.toFloat()..ConsoleSettings.MAX_FONT_SIZE.toFloat(),
            onChange   = { update(settings.copy(fontSize = it.roundToInt())) },
        )
        SettingsSwitchRow(s.consoleSecWrap, settings.wrapText) { update(settings.copy(wrapText = it)) }
        SettingsSwitchRow(s.consoleSecGutter, settings.showGutterStrip) { update(settings.copy(showGutterStrip = it)) }
        SettingsSwitchRow(s.consoleSecTimestamps, settings.showTimestamps) { update(settings.copy(showTimestamps = it)) }
        SliderRow(
            title      = s.consoleSecBuffer,
            valueLabel = "${settings.maxInMemoryLines}",
            value      = settings.maxInMemoryLines.toFloat(),
            range      = ConsoleSettings.MIN_IN_MEMORY_LINES.toFloat()..ConsoleSettings.MAX_IN_MEMORY_LINES.toFloat(),
            onChange   = { update(settings.copy(maxInMemoryLines = it.roundToInt())) },
        )

        Spacer(Modifier.height(6.dp))
        SettingsSectionTitle(s.consoleSecColors)
        ColorRow(s.consoleSecColorInfo, settings.infoColor, s.consoleSecColorAuto) { update(settings.copy(infoColor = it)) }
        ColorRow(s.consoleSecColorWarn, settings.warnColor, s.consoleSecColorAuto) { update(settings.copy(warnColor = it)) }
        ColorRow(s.consoleSecColorError, settings.errorColor, s.consoleSecColorAuto) { update(settings.copy(errorColor = it)) }

        Text(
            text  = s.consoleSecApplyNote,
            style = MaterialTheme.typography.bodySmall,
            color = NxTheme.colors.textSecondary,
        )

        Spacer(Modifier.height(6.dp))
        SettingsSectionTitle(s.consoleSecHighlightRules)
        settings.highlightRules.forEachIndexed { i, rule ->
            HighlightRuleCard(
                rule     = rule,
                onChange = { changed -> update(settings.copy(highlightRules = settings.highlightRules.mapIndexed { j, r -> if (j == i) changed else r })) },
                onDelete = { update(settings.copy(highlightRules = settings.highlightRules.filterIndexed { j, _ -> j != i })) },
            )
        }
        if (settings.highlightRules.isEmpty()) EmptyRulesHint(s.consoleSecRulesEmpty)
        NxButton(
            label   = s.consoleSecAddRule,
            onClick = { update(settings.copy(highlightRules = settings.highlightRules + HighlightRule())) },
            style   = NxButtonStyle.Secondary,
            icon    = NxIcon.Add,
            compact = true,
        )

        Spacer(Modifier.height(6.dp))
        SettingsSectionTitle(s.consoleSecFilterRules)
        settings.filterRules.forEachIndexed { i, rule ->
            FilterRuleCard(
                rule     = rule,
                onChange = { changed -> update(settings.copy(filterRules = settings.filterRules.mapIndexed { j, r -> if (j == i) changed else r })) },
                onDelete = { update(settings.copy(filterRules = settings.filterRules.filterIndexed { j, _ -> j != i })) },
            )
        }
        if (settings.filterRules.isEmpty()) EmptyRulesHint(s.consoleSecRulesEmpty)
        NxButton(
            label   = s.consoleSecAddRule,
            onClick = { update(settings.copy(filterRules = settings.filterRules + FilterRule())) },
            style   = NxButtonStyle.Secondary,
            icon    = NxIcon.Add,
            compact = true,
        )

        Spacer(Modifier.height(6.dp))
        SettingsSectionTitle(s.consoleSecArt)
        settings.customArt.forEachIndexed { i, art ->
            ArtCard(
                art      = art,
                onDelete = { update(settings.copy(customArt = settings.customArt.filterIndexed { j, _ -> j != i })) },
            )
        }
        if (settings.customArt.isEmpty()) EmptyRulesHint(s.consoleSecArtEmpty)
        ArtAdder(
            draft       = artDraft,
            onDraft     = { artDraft = it },
            onAdd       = {
                val a = artDraft.trim('\n')
                if (a.isNotBlank()) { update(settings.copy(customArt = settings.customArt + a)); artDraft = "" }
            },
            addLabel    = s.consoleSecArtAdd,
            placeholder = s.consoleSecArtPaste,
        )
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val style = LocalStyle.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(settingsRowBackground())
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
            Text(valueLabel, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary)
        }
        Slider(
            value         = value,
            onValueChange = onChange,
            valueRange    = range,
            colors        = SliderDefaults.colors(
                thumbColor       = NxTheme.colors.primary,
                activeTrackColor = NxTheme.colors.primary,
            ),
        )
    }
}

/**
 * Severity colour row: swatch + hex field. Blank/invalid hex -> null (the console
 * falls back to the theme colour); [autoLabel] clears it back to that default.
 */
@Composable
private fun ColorRow(title: String, hex: String?, autoLabel: String, onChange: (String?) -> Unit) {
    val style = LocalStyle.current
    var text by remember(hex) { mutableStateOf(hex.orEmpty()) }
    val parsed = text.takeIf { it.isNotBlank() }?.let { runCatching { CustomTheme.parseHexColor(it) }.getOrNull() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(settingsRowBackground())
            .padding(16.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(parsed ?: Color.Transparent)
                .border(1.dp, NxTheme.colors.outline, RoundedCornerShape(6.dp)),
        )
        Box(
            Modifier
                .width(110.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(NxTheme.colors.surface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                value         = text,
                onValueChange = { v -> text = v; onChange(v.ifBlank { null }) },
                singleLine    = true,
                textStyle     = MaterialTheme.typography.bodySmall.copy(color = NxTheme.colors.textPrimary),
                cursorBrush   = SolidColor(NxTheme.colors.primary),
            ) { inner ->
                if (text.isEmpty()) {
                    Text("#RRGGBB", style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary.copy(alpha = 0.6f))
                }
                inner()
            }
        }
        Text(
            text     = autoLabel,
            style    = MaterialTheme.typography.bodySmall,
            color    = NxTheme.colors.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { text = ""; onChange(null) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun HighlightRuleCard(rule: HighlightRule, onChange: (HighlightRule) -> Unit, onDelete: () -> Unit) {
    val s = LocalStrings.current
    val style = LocalStyle.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(settingsRowBackground())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuleSwitch(rule.enabled) { onChange(rule.copy(enabled = it)) }
            LineField(rule.pattern, { onChange(rule.copy(pattern = it)) }, s.consoleSecRulePattern, Modifier.weight(1f))
            DeleteIcon(onDelete)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipToggle(s.consoleSecRegex, rule.regex) { onChange(rule.copy(regex = !rule.regex)) }
            Swatch(rule.colorHex)
            LineField(rule.colorHex, { onChange(rule.copy(colorHex = it)) }, "#RRGGBB", Modifier.width(110.dp))
            ChipToggle(s.consoleSecBold, rule.bold) { onChange(rule.copy(bold = !rule.bold)) }
        }
    }
}

@Composable
private fun FilterRuleCard(rule: FilterRule, onChange: (FilterRule) -> Unit, onDelete: () -> Unit) {
    val s = LocalStrings.current
    val style = LocalStyle.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(settingsRowBackground())
            .padding(12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RuleSwitch(rule.enabled) { onChange(rule.copy(enabled = it)) }
        LineField(rule.pattern, { onChange(rule.copy(pattern = it)) }, s.consoleSecRulePattern, Modifier.weight(1f))
        ChipToggle(s.consoleSecRegex, rule.regex) { onChange(rule.copy(regex = !rule.regex)) }
        DeleteIcon(onDelete)
    }
}

@Composable
private fun RuleSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    NxSwitch(checked = checked, onCheckedChange = onChange)
}

@Composable
private fun LineField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(NxTheme.colors.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        BasicTextField(
            value         = value,
            onValueChange = onChange,
            singleLine    = true,
            textStyle     = MaterialTheme.typography.bodySmall.copy(color = NxTheme.colors.textPrimary),
            cursorBrush   = SolidColor(NxTheme.colors.primary),
        ) { inner ->
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary.copy(alpha = 0.6f))
            }
            inner()
        }
    }
}

@Composable
private fun Swatch(hex: String) {
    val color = hex.takeIf { it.isNotBlank() }?.let { runCatching { CustomTheme.parseHexColor(it) }.getOrNull() }
    Box(
        Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color ?: Color.Transparent)
            .border(1.dp, NxTheme.colors.outline, RoundedCornerShape(6.dp)),
    )
}

@Composable
private fun ChipToggle(label: String, on: Boolean, onToggle: () -> Unit) {
    Text(
        text       = label,
        style      = MaterialTheme.typography.labelSmall,
        color      = if (on) NxTheme.colors.primary else NxTheme.colors.textSecondary,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        modifier   = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) NxTheme.colors.primary.copy(alpha = 0.18f) else NxTheme.colors.surface)
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun DeleteIcon(onDelete: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDelete).padding(6.dp)) {
        Symbol(NxIcon.Delete, contentDescription = null, tint = NxTheme.colors.error, size = 18.dp)
    }
}

@Composable
private fun EmptyRulesHint(text: String) {
    Text(
        text,
        style    = MaterialTheme.typography.bodySmall,
        color    = NxTheme.colors.textSecondary.copy(alpha = 0.7f),
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun ArtCard(art: String, onDelete: () -> Unit) {
    val style = LocalStyle.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(settingsRowBackground())
            .padding(12.dp),
        verticalAlignment     = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Rendered in the Braille font (DejaVu Sans) so Braille previews aren't tofu;
        // no wrap + horizontal scroll keeps wide pictures intact, ellipsis caps height.
        Text(
            text     = art,
            style    = TextStyle(fontFamily = nexiraBrailleFamily(), fontSize = 11.sp, lineHeight = 12.sp),
            color    = NxTheme.colors.textSecondary,
            softWrap = false,
            maxLines = 10,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).heightIn(max = 140.dp).horizontalScroll(rememberScrollState()),
        )
        DeleteIcon(onDelete)
    }
}

@Composable
private fun ArtAdder(
    draft: String,
    onDraft: (String) -> Unit,
    onAdd: () -> Unit,
    addLabel: String,
    placeholder: String,
) {
    val style = LocalStyle.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(settingsRowBackground())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(NxTheme.colors.surface)
                .padding(10.dp),
        ) {
            BasicTextField(
                value         = draft,
                onValueChange = onDraft,
                singleLine    = false,
                textStyle     = TextStyle(fontFamily = nexiraBrailleFamily(), fontSize = 12.sp, color = NxTheme.colors.textPrimary),
                cursorBrush   = SolidColor(NxTheme.colors.primary),
                modifier      = Modifier.fillMaxWidth(),
            ) { inner ->
                if (draft.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary.copy(alpha = 0.6f))
                }
                inner()
            }
        }
        NxButton(label = addLabel, onClick = onAdd, style = NxButtonStyle.Secondary, icon = NxIcon.Add, compact = true)
    }
}
