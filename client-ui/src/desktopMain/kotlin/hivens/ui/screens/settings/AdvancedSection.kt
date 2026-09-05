package hivens.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.config.ExperimentalProtocolOverride
import hivens.config.Protocol
import hivens.core.data.AmberUpdatePolicy
import hivens.core.data.SettingsData
import hivens.core.diag.ActionRing
import hivens.launcher.platform.DataDirMover
import hivens.launcher.platform.PlatformPaths
import hivens.update.DesktopIntegration
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxField
import hivens.ui.nx.NxSection
import hivens.ui.nx.NxToggle
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetField
import hivens.ui.puppet.PuppetToggle
import hivens.ui.theme.NxTheme
import hivens.ui.utils.rememberFileDialogSettings
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

private val log = LoggerFactory.getLogger("AdvancedSection")

/**
 * Everything a user reaches for once the defaults stop fitting, on three planes per
 * the island model: what updates itself (the launcher and the packs it installed),
 * what a launch is handed (heap, JVM args, the version string the protocol sees),
 * and where the data lives.
 *
 * These knobs used to sit behind a master switch labelled "experimental", which
 * defaulted on and therefore said nothing about any of them -- while gating, among
 * others, the heap every instance launches with. Each one now stands on its own
 * default. What is genuinely experimental is the SmartyCraft client auto-sync, and
 * it lives beside the rest of the SmartyCraft controls.
 *
 * The data-directory move is scheduled on restart via [DataDirMover] (the copy runs
 * on next start, before [PlatformPaths] is consulted), so this screen only persists
 * the intent and prompts to restart.
 */
@Composable
internal fun AdvancedSection(
    paths: PlatformPaths,
    form: SettingsFormState,
    save: () -> Unit,
    initialSettings: SettingsData,
) {
    val s = LocalStrings.current
    val desktop: DesktopIntegration = koinInject()

    var pendingTarget by remember { mutableStateOf<Path?>(null) }
    var showError     by remember { mutableStateOf<String?>(null) }
    var desktopDone   by remember { mutableStateOf(false) }
    val moveScope     = rememberCoroutineScope()
    val dialogSettings = rememberFileDialogSettings(s.settingsDataDirMove)

    NxSection(s.settingsSectionUpdates) {
        NxToggle(s.settingsPreReleases, form.preReleasesEnabled, description = s.settingsPreReleasesDesc) {
            form.preReleasesEnabled = it; save()
        }

        NxToggle(s.settingsMandatoryUpdates, form.mandatoryUpdates, description = s.settingsMandatoryUpdatesDesc, icon = NxIcon.Update) {
            form.mandatoryUpdates = it; save()
        }
        PuppetToggle("settings.mandatoryUpdates", form.mandatoryUpdates) { form.mandatoryUpdates = it; save() }

        NxToggle(s.settingsAutoUpdatePacks, form.autoUpdatePacks, description = s.settingsAutoUpdatePacksDesc, icon = NxIcon.CloudDownload) {
            form.autoUpdatePacks = it; save()
        }
        PuppetToggle("settings.autoUpdatePacks", form.autoUpdatePacks) { form.autoUpdatePacks = it; save() }

        // Only shown while the unattended pass can run: with auto-update off nothing
        // classifies a build, so the policy governs nothing. Sits flush with the rest
        // of the plane, like every other PickerBlock.
        if (form.autoUpdatePacks) {
            PickerBlock(s.settingsAmberPolicy, s.settingsAmberPolicyDesc) {
                NxChoiceChip(s.settingsAmberPolicyAsk, form.amberUpdatePolicy == AmberUpdatePolicy.Ask) {
                    form.amberUpdatePolicy = AmberUpdatePolicy.Ask; save()
                }
                NxChoiceChip(s.settingsAmberPolicyApply, form.amberUpdatePolicy == AmberUpdatePolicy.SnapshotThenApply) {
                    form.amberUpdatePolicy = AmberUpdatePolicy.SnapshotThenApply; save()
                }
                NxChoiceChip(s.settingsAmberPolicyHold, form.amberUpdatePolicy == AmberUpdatePolicy.Hold) {
                    form.amberUpdatePolicy = AmberUpdatePolicy.Hold; save()
                }
            }
            AmberUpdatePolicy.entries.forEach { policy ->
                PuppetClick("settings.amberPolicy.${policy.name}") { form.amberUpdatePolicy = policy; save() }
            }
        }

        // Carry-over from the removed update-manager window: install a .desktop menu
        // entry (Linux/AppImage). A plain relocated action, NOT a designed OS-
        // integration feature -- that redesign is a separate task.
        if (desktop.isSupported()) {
            NxButton(
                label   = if (desktopDone) s.updateManagerDesktopDone else s.updateManagerInstallDesktop,
                style   = NxButtonStyle.Secondary,
                compact = true,
                enabled = !desktopDone,
                onClick = {
                    moveScope.launch {
                        withContext(Dispatchers.IO) { desktop.installEntry() }
                            .onSuccess { desktopDone = true }
                            .onFailure { log.warn("desktop entry install failed", it) }
                    }
                },
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    NxSection(s.settingsSectionLaunch) {
        NxToggle(s.settingsAdaptiveMemory, form.adaptiveMemoryEnabled, description = s.settingsAdaptiveMemoryDesc, icon = NxIcon.Memory) {
            form.adaptiveMemoryEnabled = it; save()
        }
        PuppetToggle("settings.adaptiveMemory", form.adaptiveMemoryEnabled) { form.adaptiveMemoryEnabled = it; save() }

        NxToggle(s.settingsJvmBuilder, form.jvmBuilderEnabled, description = s.settingsJvmBuilderDesc, icon = NxIcon.Tune) {
            form.jvmBuilderEnabled = it; save()
        }
        PuppetToggle("settings.jvmBuilder", form.jvmBuilderEnabled) { form.jvmBuilderEnabled = it; save() }

        NxToggle(s.settingsMimicVersion, form.mimicOverrideEnabled, description = s.settingsMimicVersionDesc, icon = NxIcon.Tag) {
            form.mimicOverrideEnabled = it; save()
        }
        PuppetToggle("settings.mimicVersion", form.mimicOverrideEnabled) { form.mimicOverrideEnabled = it; save() }

        // The revealed field is debounced (400 ms after the last keystroke) because
        // save() does a synchronous file write and applies the value to live protocol
        // traffic; per-keystroke saves would stutter and push partial values. The
        // toggle flip persists immediately via its own callback.
        if (form.mimicOverrideEnabled) {
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
                modifier      = Modifier.fillMaxWidth(),
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

    Spacer(Modifier.height(16.dp))

    NxSection(s.settingsSectionDataDir) {
        Text(s.settingsDataDirCurrent, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary)
        Text(
            text       = paths.dataDir.toAbsolutePath().toString(),
            color      = NxTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            style      = MaterialTheme.typography.bodyMedium,
        )
        NxButton(
            label   = s.settingsDataDirMove,
            style   = NxButtonStyle.Secondary,
            compact = true,
            onClick = {
                showError = null
                moveScope.launch {
                    // filekit uses xdg-desktop-portal on Linux (native Hyprland / KDE /
                    // GNOME picker), IFileDialog on Windows, NSOpenPanel on macOS. The
                    // ProGuard keep on io.github.vinceglb.filekit.** is what makes the
                    // directory-hint overload survive shrinking (the 2.3.1 dead-button bug).
                    val pickResult = runCatching {
                        FileKit.openDirectoryPicker(
                            directory      = PlatformFile(paths.dataDir.toFile()),
                            dialogSettings = dialogSettings,
                        )
                    }
                    val pickedFile = pickResult.getOrElse { ex ->
                        // Surface the failure so a "dead button" stops looking dead.
                        log.warn("openDirectoryPicker failed", ex)
                        showError = s.settingsDataDirErrorPickerFailed(ex.message ?: ex.javaClass.simpleName)
                        return@launch
                    } ?: return@launch

                    val picked = Paths.get(pickedFile.path)
                    if (picked.toAbsolutePath().normalize() == paths.dataDir.toAbsolutePath().normalize()) {
                        showError = s.settingsDataDirErrorSamePath
                        return@launch
                    }
                    val populated = withContext(Dispatchers.IO) {
                        runCatching {
                            Files.exists(picked) && Files.list(picked).use { it.findAny().isPresent }
                        }.getOrDefault(false)
                    }
                    if (populated) {
                        showError = s.settingsDataDirErrorNotEmpty
                        return@launch
                    }
                    pendingTarget = picked
                }
            },
        )
        if (showError != null) {
            Text(showError!!, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.error)
        }
    }

    if (pendingTarget != null) {
        val target = pendingTarget!!
        AlertDialog(
            onDismissRequest = { pendingTarget = null },
            title = { Text(s.settingsDataDirConfirmTitle) },
            text  = {
                Text(s.settingsDataDirConfirmBody(
                    paths.dataDir.toAbsolutePath().toString(),
                    target.toAbsolutePath().toString(),
                ))
            },
            confirmButton = {
                NxButton(label = s.settingsDataDirQuitNow, onClick = {
                    val ok = DataDirMover.schedule(source = paths.dataDir, target = target)
                    if (ok) {
                        ActionRing.record("Data-dir move scheduled: ${paths.dataDir} -> $target -- quitting for restart")
                        // Hard exit -- user explicitly clicked "Quit now". The pending move
                        // applies only after restart, so a clean process termination is right.
                        exitProcess(0)
                    } else {
                        // Schedule refused (target validation raced); let the user re-pick.
                        pendingTarget = null
                    }
                })
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingTarget = null }, shape = MaterialTheme.shapes.small) {
                    Text(s.sslWarningCancel)
                }
            },
            containerColor = NxTheme.colors.surface,
        )
    }
}
