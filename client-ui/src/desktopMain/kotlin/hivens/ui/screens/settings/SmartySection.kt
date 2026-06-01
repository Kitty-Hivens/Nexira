package hivens.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetToggle
import hivens.ui.theme.CelestiaTheme

/**
 * Smarty server controls. Two switches that govern how a raw SmartyCraft
 * server's mods are handled at sync:
 *  - swap the upstream Smarty surveillance coremod for the open-smrt-network
 *    helper, and
 *  - strict verification that deletes any jar the server manifest does not
 *    list (the description says outright that user-added mods go too).
 * Both default on. The actual swap + prune live in the launcher
 * (`SmartyModPlanner` / `FileDownloadService`); this screen only flips the
 * persisted flags.
 */
@Composable
internal fun SmartySection(
    form: SettingsFormState,
    save: () -> Unit,
) {
    val s = LocalStrings.current

    SettingsSectionTitle(s.settingsSectionSmarty)

    Column(
        modifier = Modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsRowWithDescription(
            title           = s.settingsOpenSmrtHelperTitle,
            description     = s.settingsOpenSmrtHelperDesc,
            icon            = Icons.Default.SwapHoriz,
            iconTint        = CelestiaTheme.colors.primary,
            checked         = form.useOpenSmrtHelper,
            enabled         = true,
            onCheckedChange = { form.useOpenSmrtHelper = it; save() },
        )
        PuppetToggle("settings.useOpenSmrtHelper", form.useOpenSmrtHelper) {
            form.useOpenSmrtHelper = it; save()
        }

        SettingsRowWithDescription(
            title           = s.settingsStrictModCheckTitle,
            description     = s.settingsStrictModCheckDesc,
            icon            = Icons.Default.Rule,
            iconTint        = CelestiaTheme.colors.primary,
            checked         = form.strictModVerification,
            enabled         = true,
            onCheckedChange = { form.strictModVerification = it; save() },
        )
        PuppetToggle("settings.strictModVerification", form.strictModVerification) {
            form.strictModVerification = it; save()
        }
    }
}
