package hivens.ui.screens.settings

import androidx.compose.runtime.Composable
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxSection
import hivens.ui.nx.NxToggle
import hivens.ui.puppet.PuppetToggle

/**
 * Smarty server controls. Switches that govern how a raw SmartyCraft server's
 * mods are handled at sync: swap the upstream Smarty surveillance coremod for the
 * open-smrt-network helper, strict verification that deletes any jar the manifest
 * does not list, the network agent, and the SmartyCraft authlib swap. All default
 * on. The actual swap + prune live in the launcher (`SmartyModPlanner` /
 * `FileDownloadService`); this screen only flips the persisted flags.
 */
@Composable
internal fun SmartySection(
    form: SettingsFormState,
    save: () -> Unit,
) {
    val s = LocalStrings.current

    NxSection(s.settingsSectionSmarty) {
        NxToggle(s.settingsOpenSmrtHelperTitle, form.useOpenSmrtHelper, description = s.settingsOpenSmrtHelperDesc, icon = NxIcon.SwapHoriz) {
            form.useOpenSmrtHelper = it; save()
        }
        PuppetToggle("settings.useOpenSmrtHelper", form.useOpenSmrtHelper) { form.useOpenSmrtHelper = it; save() }

        NxToggle(s.settingsStrictModCheckTitle, form.strictModVerification, description = s.settingsStrictModCheckDesc, icon = NxIcon.Rule) {
            form.strictModVerification = it; save()
        }
        PuppetToggle("settings.strictModVerification", form.strictModVerification) { form.strictModVerification = it; save() }

        NxToggle(s.settingsNetworkAgentTitle, form.useNetworkAgent, description = s.settingsNetworkAgentDesc, icon = NxIcon.Lan) {
            form.useNetworkAgent = it; save()
        }
        PuppetToggle("settings.useNetworkAgent", form.useNetworkAgent) { form.useNetworkAgent = it; save() }

        NxToggle(s.settingsSmartyAuthLibTitle, form.useSmartycraftAuthLib, description = s.settingsSmartyAuthLibDesc, icon = NxIcon.VpnKey) {
            form.useSmartycraftAuthLib = it; save()
        }
        PuppetToggle("settings.useSmartycraftAuthLib", form.useSmartycraftAuthLib) { form.useSmartycraftAuthLib = it; save() }

        // Client auto-sync sits here rather than with the instance auto-update it
        // resembles: it re-runs the SmartyCraft sync, so it is that server's
        // mechanism end to end. Its two standing limits are in the copy rather than
        // in a warning icon -- a two-factor account is never logged in from a
        // background pass (the login would revoke the code-confirmed session), and
        // the raw-server path is on its way out, so neither is a defect awaiting
        // a fix.
        //
        // No compat grading either: `classifyCompat` reads an installed manifest
        // snapshot, which only a mirror instance carries. Nothing here is graded
        // green or amber, so the policy picker on the Advanced plane does not apply.
        NxToggle(s.settingsAutoSyncAllPacks, form.autoSyncAllPacks, description = s.settingsAutoSyncAllPacksDesc, icon = NxIcon.Sync) {
            form.autoSyncAllPacks = it; save()
        }
        PuppetToggle("settings.autoSyncAllPacks", form.autoSyncAllPacks) { form.autoSyncAllPacks = it; save() }
    }
}
