package hivens.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.config.ExperimentalProtocolOverride
import hivens.config.Protocol
import hivens.core.data.SettingsData
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetField
import hivens.ui.puppet.PuppetToggle
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Opt-in experimental features. Master toggle gates every child so a
 * single switch flip lets the user back out of every alpha-grade
 * feature at once. Children stay grayed out (not hidden) when the
 * master is off, so the user can see what they're opting back into.
 *
 * Includes: mandatory-updates floor, prerelease channel, autosync,
 * JVM-args builder unlock, mimic-launcher-version override (with a
 * debounced text input revealed when its toggle is on).
 */
@Composable
internal fun ExperimentalSection(
    form: SettingsFormState,
    save: () -> Unit,
    initialSettings: SettingsData,
) {
    val s = LocalStrings.current

    SettingsSectionTitle(s.settingsSectionExperimental)

    // Master toggle
    SettingsRowWithDescription(
        title          = s.settingsExperimentalMaster,
        description    = s.settingsExperimentalMasterDesc,
        icon           = Icons.Default.Science,
        iconTint       = CelestiaTheme.colors.primary,
        checked        = form.experimentalEnabled,
        enabled        = true,
        onCheckedChange = { form.experimentalEnabled = it; save() }
    )
    PuppetToggle("settings.experimental", form.experimentalEnabled) { form.experimentalEnabled = it; save() }

    Spacer(Modifier.height(4.dp))

    SettingsRowWithDescription(
        title          = s.settingsMandatoryUpdates,
        description    = s.settingsMandatoryUpdatesDesc,
        icon           = Icons.Default.Update,
        iconTint       = CelestiaTheme.colors.primary,
        checked        = form.experimentalEnabled && form.mandatoryUpdates,
        enabled        = form.experimentalEnabled,
        onCheckedChange = { form.mandatoryUpdates = it; save() }
    )
    // Mirror the UI's enabled-gating: master switch off => can't touch sub-toggles.
    PuppetToggle("settings.mandatoryUpdates", form.mandatoryUpdates, enabled = form.experimentalEnabled) {
        form.mandatoryUpdates = it; save()
    }

    Spacer(Modifier.height(4.dp))

    SettingsRowWithDescription(
        title          = s.settingsPrereleaseChannel,
        description    = s.settingsPrereleaseChannelDesc,
        icon           = Icons.Default.NewReleases,
        iconTint       = CelestiaTheme.colors.primary,
        checked        = form.experimentalEnabled && form.prereleaseChannel,
        enabled        = form.experimentalEnabled,
        onCheckedChange = { form.prereleaseChannel = it; save() }
    )
    PuppetToggle("settings.prereleaseChannel", form.prereleaseChannel, enabled = form.experimentalEnabled) {
        form.prereleaseChannel = it; save()
    }

    Spacer(Modifier.height(4.dp))

    SettingsRowWithDescription(
        title          = s.settingsAutoSyncAllPacks,
        description    = s.settingsAutoSyncAllPacksDesc,
        icon           = Icons.Default.Sync,
        iconTint       = CelestiaTheme.colors.primary,
        checked        = form.experimentalEnabled && form.autoSyncAllPacks,
        enabled        = form.experimentalEnabled,
        onCheckedChange = { form.autoSyncAllPacks = it; save() }
    )
    PuppetToggle("settings.autoSyncAllPacks", form.autoSyncAllPacks, enabled = form.experimentalEnabled) {
        form.autoSyncAllPacks = it; save()
    }

    Spacer(Modifier.height(4.dp))

    SettingsRowWithDescription(
        title          = s.settingsJvmBuilder,
        description    = s.settingsJvmBuilderDesc,
        icon           = Icons.Default.Tune,
        iconTint       = CelestiaTheme.colors.primary,
        checked        = form.experimentalEnabled && form.jvmBuilderEnabled,
        enabled        = form.experimentalEnabled,
        onCheckedChange = { form.jvmBuilderEnabled = it; save() }
    )
    PuppetToggle("settings.jvmBuilder", form.jvmBuilderEnabled, enabled = form.experimentalEnabled) {
        form.jvmBuilderEnabled = it; save()
    }

    Spacer(Modifier.height(4.dp))

    SettingsRowWithDescription(
        title          = s.settingsAdaptiveMemory,
        description    = s.settingsAdaptiveMemoryDesc,
        icon           = Icons.Default.Memory,
        iconTint       = CelestiaTheme.colors.primary,
        checked        = form.experimentalEnabled && form.adaptiveMemoryEnabled,
        enabled        = form.experimentalEnabled,
        onCheckedChange = { form.adaptiveMemoryEnabled = it; save() }
    )
    PuppetToggle("settings.adaptiveMemory", form.adaptiveMemoryEnabled, enabled = form.experimentalEnabled) {
        form.adaptiveMemoryEnabled = it; save()
    }

    Spacer(Modifier.height(4.dp))

    // ── Mimic launcher version override ───────────────────────
    // Toggle row + revealed text input. Doubly gated: master
    // experimental switch AND the row's own toggle. Saving an
    // empty/blank text falls back to the shipped default via the
    // normalisation in save().
    SettingsRowWithDescription(
        title          = s.settingsMimicVersion,
        description    = s.settingsMimicVersionDesc,
        icon           = Icons.Default.Tag,
        iconTint       = CelestiaTheme.colors.primary,
        checked        = form.experimentalEnabled && form.mimicOverrideEnabled,
        enabled        = form.experimentalEnabled,
        onCheckedChange = { form.mimicOverrideEnabled = it; save() }
    )
    PuppetToggle("settings.mimicVersion", form.mimicOverrideEnabled, enabled = form.experimentalEnabled) {
        form.mimicOverrideEnabled = it; save()
    }
    if (form.experimentalEnabled && form.mimicOverrideEnabled) {
        Spacer(Modifier.height(4.dp))
        // Debounce text-field writes: onValueChange fires per keystroke
        // and save() runs a synchronous file write + applies the new
        // value to live protocol traffic. Calling save() on every
        // keystroke would stutter the UI on slow disks and push
        // transient partial values ("3", "3.", "3.6") to the next
        // protocol call before the user is done. The LaunchedEffect
        // below waits 400 ms after the last keystroke and then
        // commits. Toggle-flip persists immediately via its own
        // onCheckedChange (above) so the dependency between the toggle
        // and the field stays intuitive.
        OutlinedTextField(
            // Filter at every keystroke -- the value propagates into a
            // User-Agent header, a JVM system property, and the spawned
            // game's -Dminecraft.launcher.version argv, all of which
            // reject non-ASCII. A user with a Cyrillic keyboard layout
            // accidentally typing here used to break login with an opaque
            // "Network Error". Protocol.setMimicLauncherVersion repeats
            // the same check as defense for hand-edited or older-version
            // persistence files.
            value           = form.mimicVersionText,
            onValueChange   = { newValue ->
                form.mimicVersionText = newValue.filter {
                    @OptIn(ExperimentalProtocolOverride::class)
                    it in Protocol.MIMIC_VERSION_ALLOWED_CHARS
                }
            },
            singleLine      = true,
            placeholder     = {
                Text(
                    s.settingsMimicVersionPlaceholder(
                        Protocol.DEFAULT_MIMIC_LAUNCHER_VERSION
                    ),
                    color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.55f),
                )
            },
            modifier        = Modifier
                .fillMaxWidth()
                .padding(start = 56.dp),
        )
        PuppetField("settings.mimicVersion.text", form.mimicVersionText) { newValue ->
            form.mimicVersionText = newValue.filter {
                @OptIn(ExperimentalProtocolOverride::class)
                it in Protocol.MIMIC_VERSION_ALLOWED_CHARS
            }
        }
        LaunchedEffect(form.mimicVersionText) {
            // Skip the initial-composition fire when the field equals
            // the persisted value.
            if (form.mimicVersionText == (initialSettings.mimicVersionOverride ?: "")) {
                return@LaunchedEffect
            }
            delay(400.milliseconds)
            save()
        }
    }
}
