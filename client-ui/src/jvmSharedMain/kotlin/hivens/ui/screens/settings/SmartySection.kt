package hivens.ui.screens.settings

import androidx.compose.runtime.Composable
import hivens.ui.i18n.nexiraStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxSection
import hivens.ui.nx.NxToggle
import hivens.ui.puppet.PuppetToggle

/**
 * How an SC-bound pack reaches SmartyCraft: the authlib-redirect agent, and the
 * older swap of SC's own patched authlib jar. Both are no-ops for a pack that
 * declares no SC binding.
 *
 * The mechanism lives in the launcher (`LauncherService`); this screen only
 * flips the persisted flags.
 */
@Composable
internal fun SmartySection(
    form: SettingsFormState,
    save: () -> Unit,
) {
    val s = nexiraStrings()

    NxSection(s.text("settingsSectionSmarty")) {
        NxToggle(s.text("settingsNetworkAgentTitle"), form.useNetworkAgent, description = s.text("settingsNetworkAgentDesc"), icon = NxIcon.Lan) {
            form.useNetworkAgent = it; save()
        }
        PuppetToggle("settings.useNetworkAgent", form.useNetworkAgent) { form.useNetworkAgent = it; save() }

        NxToggle(s.text("settingsSmartyAuthLibTitle"), form.useSmartycraftAuthLib, description = s.text("settingsSmartyAuthLibDesc"), icon = NxIcon.VpnKey) {
            form.useSmartycraftAuthLib = it; save()
        }
        PuppetToggle("settings.useSmartycraftAuthLib", form.useSmartycraftAuthLib) { form.useSmartycraftAuthLib = it; save() }

        NxToggle(s.text("settingsReuseSessionTitle"), form.experimentalReuseSession, description = s.text("settingsReuseSessionDesc"), icon = NxIcon.Sync) {
            form.experimentalReuseSession = it; save()
        }
        PuppetToggle("settings.experimentalReuseSession", form.experimentalReuseSession) { form.experimentalReuseSession = it; save() }
    }
}
