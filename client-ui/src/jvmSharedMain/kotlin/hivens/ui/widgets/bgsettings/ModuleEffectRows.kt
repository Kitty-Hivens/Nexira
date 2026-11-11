package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalAppLocale
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxSlider
import hivens.ui.nx.NxToggle
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.widgets.modules.WidgetModules
import hivens.ui.widgets.modules.WidgetModules.Contributed
import hivens.widget.api.ScreenEffect
import hivens.widget.api.ModuleSetting
import hivens.widget.api.ModuleValues
import org.koin.compose.getKoin

/**
 * The screen effects the modules brought, each with a switch and, once it is on, the controls its
 * module declared for it.
 *
 * The launcher knows no effect and no control by name: what is listed, what it is called and what
 * can be adjusted all come from the module, in the person's language where the module ships it,
 * and what is set is stored by the module system under the effect. Absent altogether while no
 * module carries an effect, since a row offering something nothing can answer is clutter.
 */
@Composable
internal fun ModuleEffectRows(modifier: Modifier = Modifier) {
    val modules = getKoin().getOrNull<WidgetModules>() ?: return
    val effects = modules.state.collectAsState().value.effects
    if (effects.isEmpty()) return
    val stored by modules.settings.collectAsState()
    val s = LocalStrings.current

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(s.moduleSettingsTitle)
        effects.forEach { entry ->
            EffectBlock(entry, stored[entry.scope].orEmpty()) { key, value -> modules.setEffectSetting(entry.scope, key, value) }
        }
        if (effects.any { stored[it.scope]?.get(WidgetModules.EFFECT_ENABLED) == "true" }) {
            Text(
                text  = s.screenEffectWarning,
                style = MaterialTheme.typography.bodySmall,
                color = NxInk.quiet,
            )
        }
    }
}

@Composable
private fun EffectBlock(entry: Contributed<ScreenEffect>, stored: Map<String, String>, onSet: (key: String, value: String) -> Unit) {
    val tag = LocalAppLocale.current.tag
    val text = { key: String -> entry.strings.get(key, tag) }
    val on = stored[WidgetModules.EFFECT_ENABLED]?.toBooleanStrictOrNull() == true

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NxToggle(
            label           = text(entry.item.label),
            checked         = on,
            description     = entry.moduleName,
            accent          = NxColor.lead(),
            onCheckedChange = { onSet(WidgetModules.EFFECT_ENABLED, ModuleValues.encode(it)) },
        )
        if (on) {
            val settings = remember(entry.item) { entry.item.settings }
            val values = ModuleValues(settings, stored)
            settings.forEach { setting -> SettingRow(setting, values, text, onSet) }
        }
    }
}

@Composable
private fun SettingRow(
    setting: ModuleSetting,
    values: ModuleValues,
    text: (String) -> String,
    onSet: (key: String, value: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (setting) {
            is ModuleSetting.Toggle -> NxToggle(
                label           = text(setting.label),
                checked         = values.toggle(setting.key),
                accent          = NxColor.lead(),
                onCheckedChange = { onSet(setting.key, ModuleValues.encode(it)) },
            )
            is ModuleSetting.Amount -> {
                val value = values.amount(setting.key)
                NxSlider(
                    label         = text(setting.label),
                    value         = value,
                    range         = setting.range,
                    valueText     = if (setting.percent) "${((value - setting.range.start) / (setting.range.endInclusive - setting.range.start) * 100).toInt()}%" else "%.2f".format(value),
                    onValueChange = { onSet(setting.key, ModuleValues.encode(it)) },
                    compact       = true,
                )
            }
            is ModuleSetting.Choice -> {
                val chosen = values.choice(setting.key)
                BgPicker(text(setting.label)) {
                    setting.options.forEach { option ->
                        NxChoiceChip(text(option.label), chosen == option.id) { onSet(setting.key, option.id) }
                    }
                }
            }
        }
        setting.hint?.let {
            Text(text = text(it), style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
        }
    }
}
