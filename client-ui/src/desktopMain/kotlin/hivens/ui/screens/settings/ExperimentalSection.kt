package hivens.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.config.ExperimentalProtocolOverride
import hivens.config.Protocol
import hivens.core.data.SettingsData
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxField
import hivens.ui.nx.NxSection
import hivens.ui.nx.NxToggle
import hivens.ui.puppet.PuppetField
import hivens.ui.puppet.PuppetToggle
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * Opt-in experimental features. Master toggle gates every child so a single
 * switch flip lets the user back out of every alpha-grade feature at once.
 * Children stay greyed out (not hidden) when the master is off, so the user can
 * see what they're opting back into.
 *
 * Includes: mandatory-updates floor, autosync, JVM-args builder unlock, adaptive
 * memory, and the mimic-launcher-version override (with a debounced field revealed
 * when its toggle is on).
 */
@Composable
internal fun ExperimentalSection(
    form: SettingsFormState,
    save: () -> Unit,
    initialSettings: SettingsData,
) {
    val s = LocalStrings.current

    NxSection(s.settingsSectionExperimental) {
        NxToggle(s.settingsExperimentalMaster, form.experimentalEnabled, description = s.settingsExperimentalMasterDesc, icon = NxIcon.Science) {
            form.experimentalEnabled = it; save()
        }
        PuppetToggle("settings.experimental", form.experimentalEnabled) { form.experimentalEnabled = it; save() }

        NxToggle(s.settingsMandatoryUpdates, form.experimentalEnabled && form.mandatoryUpdates, description = s.settingsMandatoryUpdatesDesc, icon = NxIcon.Update, enabled = form.experimentalEnabled) {
            form.mandatoryUpdates = it; save()
        }
        PuppetToggle("settings.mandatoryUpdates", form.mandatoryUpdates, enabled = form.experimentalEnabled) { form.mandatoryUpdates = it; save() }

        NxToggle(s.settingsAutoSyncAllPacks, form.experimentalEnabled && form.autoSyncAllPacks, description = s.settingsAutoSyncAllPacksDesc, icon = NxIcon.Sync, enabled = form.experimentalEnabled) {
            form.autoSyncAllPacks = it; save()
        }
        PuppetToggle("settings.autoSyncAllPacks", form.autoSyncAllPacks, enabled = form.experimentalEnabled) { form.autoSyncAllPacks = it; save() }

        NxToggle(s.settingsAutoUpdatePacks, form.experimentalEnabled && form.autoUpdatePacks, description = s.settingsAutoUpdatePacksDesc, icon = NxIcon.CloudDownload, enabled = form.experimentalEnabled) {
            form.autoUpdatePacks = it; save()
        }
        PuppetToggle("settings.autoUpdatePacks", form.autoUpdatePacks, enabled = form.experimentalEnabled) { form.autoUpdatePacks = it; save() }

        NxToggle(s.settingsJvmBuilder, form.experimentalEnabled && form.jvmBuilderEnabled, description = s.settingsJvmBuilderDesc, icon = NxIcon.Tune, enabled = form.experimentalEnabled) {
            form.jvmBuilderEnabled = it; save()
        }
        PuppetToggle("settings.jvmBuilder", form.jvmBuilderEnabled, enabled = form.experimentalEnabled) { form.jvmBuilderEnabled = it; save() }

        NxToggle(s.settingsAdaptiveMemory, form.experimentalEnabled && form.adaptiveMemoryEnabled, description = s.settingsAdaptiveMemoryDesc, icon = NxIcon.Memory, enabled = form.experimentalEnabled) {
            form.adaptiveMemoryEnabled = it; save()
        }
        PuppetToggle("settings.adaptiveMemory", form.adaptiveMemoryEnabled, enabled = form.experimentalEnabled) { form.adaptiveMemoryEnabled = it; save() }

        // Mimic launcher version override. Doubly gated: the master switch AND the
        // row's own toggle. The revealed field is debounced (400 ms after the last
        // keystroke) because save() does a synchronous file write and applies the
        // value to live protocol traffic; per-keystroke saves would stutter and push
        // partial values. The toggle flip persists immediately via its own callback.
        NxToggle(s.settingsMimicVersion, form.experimentalEnabled && form.mimicOverrideEnabled, description = s.settingsMimicVersionDesc, icon = NxIcon.Tag, enabled = form.experimentalEnabled) {
            form.mimicOverrideEnabled = it; save()
        }
        PuppetToggle("settings.mimicVersion", form.mimicOverrideEnabled, enabled = form.experimentalEnabled) { form.mimicOverrideEnabled = it; save() }

        if (form.experimentalEnabled && form.mimicOverrideEnabled) {
            // Filter at every keystroke: the value flows into a User-Agent header, a
            // JVM system property, and the spawned game's -Dminecraft.launcher.version
            // argv, all of which reject non-ASCII.
            NxField(
                value         = form.mimicVersionText,
                onValueChange = { newValue ->
                    form.mimicVersionText = newValue.filter {
                        @OptIn(ExperimentalProtocolOverride::class)
                        it in Protocol.MIMIC_VERSION_ALLOWED_CHARS
                    }
                },
                placeholder   = s.settingsMimicVersionPlaceholder(Protocol.DEFAULT_MIMIC_LAUNCHER_VERSION),
                modifier      = Modifier.fillMaxWidth().padding(start = 56.dp),
            )
            PuppetField("settings.mimicVersion.text", form.mimicVersionText) { newValue ->
                form.mimicVersionText = newValue.filter {
                    @OptIn(ExperimentalProtocolOverride::class)
                    it in Protocol.MIMIC_VERSION_ALLOWED_CHARS
                }
            }
            LaunchedEffect(form.mimicVersionText) {
                // Skip the initial-composition fire when the field equals the persisted value.
                if (form.mimicVersionText == (initialSettings.mimicVersionOverride ?: "")) return@LaunchedEffect
                delay(400.milliseconds)
                save()
            }
        }
    }
}
