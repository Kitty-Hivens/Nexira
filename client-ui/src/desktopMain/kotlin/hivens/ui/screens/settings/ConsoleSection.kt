package hivens.ui.screens.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.launcher.platform.PlatformPaths
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxColorField
import hivens.ui.nx.NxField
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxSection
import hivens.ui.nx.NxSlider
import hivens.ui.nx.NxSwitch
import hivens.ui.nx.NxToggle
import hivens.ui.surface.NxCard
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.theme.NxTheme
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
 * Carries Display (font / wrap / gutter / timestamps / buffer), severity
 * Colours, user highlight + filter rules, and the empty-state art manager --
 * all on the same [settings] state. Composes nx-ui primitives only.
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
        NxSection(s.consoleSecDisplay) {
            NxSlider(
                label         = s.consoleSecFontSize,
                value         = settings.fontSize.toFloat(),
                range         = ConsoleSettings.MIN_FONT_SIZE.toFloat()..ConsoleSettings.MAX_FONT_SIZE.toFloat(),
                valueText     = "${settings.fontSize} sp",
                onValueChange = { update(settings.copy(fontSize = it.roundToInt())) },
                keyStep       = 1f,
            )
            NxToggle(s.consoleSecWrap, settings.wrapText) { update(settings.copy(wrapText = it)) }
            NxToggle(s.consoleSecGutter, settings.showGutterStrip) { update(settings.copy(showGutterStrip = it)) }
            NxToggle(s.consoleSecTimestamps, settings.showTimestamps) { update(settings.copy(showTimestamps = it)) }
            NxSlider(
                label         = s.consoleSecBuffer,
                value         = settings.maxInMemoryLines.toFloat(),
                range         = ConsoleSettings.MIN_IN_MEMORY_LINES.toFloat()..ConsoleSettings.MAX_IN_MEMORY_LINES.toFloat(),
                valueText     = "${settings.maxInMemoryLines}",
                onValueChange = { update(settings.copy(maxInMemoryLines = it.roundToInt())) },
                keyStep       = 100f,
            )
        }

        NxSection(s.consoleSecColors) {
            ConsoleColorRow(s.consoleSecColorInfo, settings.infoColor, s.consoleSecColorAuto) { update(settings.copy(infoColor = it)) }
            ConsoleColorRow(s.consoleSecColorWarn, settings.warnColor, s.consoleSecColorAuto) { update(settings.copy(warnColor = it)) }
            ConsoleColorRow(s.consoleSecColorError, settings.errorColor, s.consoleSecColorAuto) { update(settings.copy(errorColor = it)) }
            Text(
                text  = s.consoleSecApplyNote,
                style = MaterialTheme.typography.bodySmall,
                color = NxTheme.colors.textSecondary,
            )
        }

        NxSection(s.consoleSecHighlightRules) {
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
        }

        NxSection(s.consoleSecFilterRules) {
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
        }

        NxSection(s.consoleSecArt) {
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
}

/** Severity colour row: a label and a library [NxColorField]; the auto label clears
 *  the override back to the theme default. */
@Composable
private fun ConsoleColorRow(label: String, hex: String?, autoLabel: String, onChange: (String?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        NxColorField(
            hex           = hex,
            onValueChange = onChange,
            onClear       = { onChange(null) },
            clearLabel    = autoLabel,
        )
    }
}

@Composable
private fun HighlightRuleCard(rule: HighlightRule, onChange: (HighlightRule) -> Unit, onDelete: () -> Unit) {
    val s = LocalStrings.current
    NxCard(glass = false) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NxSwitch(checked = rule.enabled, onCheckedChange = { onChange(rule.copy(enabled = it)) })
                NxField(rule.pattern, { onChange(rule.copy(pattern = it)) }, s.consoleSecRulePattern, Modifier.weight(1f))
                NxIconButton(NxIcon.Delete, null, onDelete, tint = NxTheme.colors.error)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NxChoiceChip(s.consoleSecRegex, rule.regex) { onChange(rule.copy(regex = !rule.regex)) }
                NxColorField(hex = rule.colorHex, onValueChange = { onChange(rule.copy(colorHex = it.orEmpty())) })
                NxChoiceChip(s.consoleSecBold, rule.bold) { onChange(rule.copy(bold = !rule.bold)) }
            }
        }
    }
}

@Composable
private fun FilterRuleCard(rule: FilterRule, onChange: (FilterRule) -> Unit, onDelete: () -> Unit) {
    val s = LocalStrings.current
    NxCard(glass = false) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NxSwitch(checked = rule.enabled, onCheckedChange = { onChange(rule.copy(enabled = it)) })
            NxField(rule.pattern, { onChange(rule.copy(pattern = it)) }, s.consoleSecRulePattern, Modifier.weight(1f))
            NxChoiceChip(s.consoleSecRegex, rule.regex) { onChange(rule.copy(regex = !rule.regex)) }
            NxIconButton(NxIcon.Delete, null, onDelete, tint = NxTheme.colors.error)
        }
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
    NxCard(glass = false) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
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
            NxIconButton(NxIcon.Delete, null, onDelete, tint = NxTheme.colors.error)
        }
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
    NxCard(glass = false) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NxField(
                value         = draft,
                onValueChange = onDraft,
                placeholder   = placeholder,
                singleLine    = false,
                modifier      = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                textStyle     = TextStyle(fontFamily = nexiraBrailleFamily(), fontSize = 12.sp, color = NxTheme.colors.textPrimary),
            )
            NxButton(label = addLabel, onClick = onAdd, style = NxButtonStyle.Secondary, icon = NxIcon.Add, compact = true)
        }
    }
}
