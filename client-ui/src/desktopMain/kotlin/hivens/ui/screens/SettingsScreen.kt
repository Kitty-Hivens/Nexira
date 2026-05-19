package hivens.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import hivens.config.Branding
import hivens.config.ExperimentalProtocolOverride
import hivens.config.Protocol
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.SettingsData
import hivens.core.diag.ActionRing
import hivens.launcher.diag.DiagnosticBundle
import hivens.launcher.diag.IssueReporter
import hivens.launcher.network.NetworkState
import hivens.launcher.platform.DataDirMover
import hivens.launcher.platform.PlatformPaths
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import hivens.ui.components.GlassCard
import hivens.ui.easter.LocalAprilFools
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.puppet.PuppetField
import hivens.ui.puppet.PuppetToggle
import hivens.ui.theme.CelestiaTheme
import org.koin.compose.koinInject
import hivens.ui.utils.SystemActions
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenThemePicker: () -> Unit,
    currentLocale: AppLocale,
    onLocaleChanged: (AppLocale) -> Unit,
    onOpenBackgroundSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {}
) {
    PuppetScreen("Settings")

    val settingsService: ISettingsService = koinInject()
    val paths: PlatformPaths              = koinInject()
    val s = LocalStrings.current
    val af = LocalAprilFools.current

    val initialSettings        = remember { settingsService.getSettings() }
    val form                   = remember { SettingsFormState(initialSettings) }
    var langDropdownExpanded   by remember { mutableStateOf(false) }
    var showSavedMessage       by remember { mutableStateOf(false) }

    // ── April Fools debug panel -- secret unlock ────────────────────────────────
    // Tap the DIAGNOSTICS section title 5 times to reveal the debug panel.
    var debugTapCount  by remember { mutableStateOf(0) }
    var showAprilDebug by remember { mutableStateOf(false) }

    fun save() {
        val toPersist = form.mergeInto(settingsService.getSettings())
        settingsService.saveSettings(toPersist)
        // Mirror to NetworkState so ChannelRouter sees it on the very next
        // request without waiting for launcher restart.
        NetworkState.setForceProxyMode(form.forceProxyMode)
        // Apply the mimic-version override immediately so the next protocol
        // handshake picks it up. Without this the user would have to restart
        // for the change to take effect, even though the system property
        // mechanism Protocol.MIMIC_LAUNCHER_VERSION reads is live.
        @OptIn(ExperimentalProtocolOverride::class)
        Protocol.setMimicLauncherVersion(toPersist.mimicVersionOverride)
        showSavedMessage = true
    }

    fun openFolder(path: String) = SystemActions.openFolder(path)

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text       = s.settingsTitle,
            style      = MaterialTheme.typography.headlineSmall,
            color      = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(24.dp))

        GlassCard(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier            = Modifier.fillMaxSize(),
                contentPadding      = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {

                // ── Interface ─────────────────────────────────────────────────
                item {
                    SettingsSectionTitle(s.settingsSectionUI)

                    // Language picker
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CelestiaTheme.colors.background.copy(alpha = 0.4f))
                            .padding(16.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Language, null, tint = CelestiaTheme.colors.primary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(16.dp))
                            Text(s.settingsLanguage, color = CelestiaTheme.colors.textPrimary)
                        }

                        Box {
                            Row(
                                Modifier
                                    .clickable { langDropdownExpanded = true }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(currentLocale.displayName, color = CelestiaTheme.colors.primary, fontWeight = FontWeight.Bold)
                                Icon(Icons.Default.ArrowDropDown, null, tint = CelestiaTheme.colors.primary)
                            }

                            DropdownMenu(
                                expanded         = langDropdownExpanded,
                                onDismissRequest = { langDropdownExpanded = false },
                                containerColor   = CelestiaTheme.colors.surface
                            ) {
                                AppLocale.entries.forEach { locale ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                locale.displayName,
                                                color      = if (locale == currentLocale) CelestiaTheme.colors.primary
                                                else CelestiaTheme.colors.textPrimary,
                                                fontWeight = if (locale == currentLocale) FontWeight.Bold
                                                else FontWeight.Normal
                                            )
                                        },
                                        onClick = { langDropdownExpanded = false; onLocaleChanged(locale) }
                                    )
                                    // Puppet: per-locale direct click. Drivers can switch
                                    // language without opening the dropdown first -- the
                                    // onLocaleChanged callback is what actually mutates
                                    // state, the dropdown is presentation only.
                                    PuppetClick("settings.language.${locale.name}") {
                                        langDropdownExpanded = false
                                        onLocaleChanged(locale)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Theme picker shortcut
                    PuppetClick("settings.openThemePicker") { onOpenThemePicker() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onOpenThemePicker)
                            .background(CelestiaTheme.colors.primary.copy(alpha = 0.1f))
                            .padding(16.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, null, tint = CelestiaTheme.colors.primary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(s.settingsThemePicker, color = CelestiaTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                                Text(s.settingsThemePickerSub, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.textSecondary)
                            }
                        }
                        Icon(Icons.Default.ArrowDropDown, null, tint = CelestiaTheme.colors.primary)
                    }

                    Spacer(Modifier.height(16.dp))

                    // Custom background shortcut
                    PuppetClick("settings.openBackground") { onOpenBackgroundSettings() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onOpenBackgroundSettings)
                            .background(CelestiaTheme.colors.surface.copy(alpha = 0.4f))
                            .padding(16.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Wallpaper, null, tint = CelestiaTheme.colors.primary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(s.settingsBackground, color = CelestiaTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                                Text(s.settingsBackgroundSub, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.textSecondary)
                            }
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = CelestiaTheme.colors.primary)
                    }

                    Spacer(Modifier.height(16.dp))

                    // Dark theme toggle
                    var themeSwitchState by remember(isDarkTheme) { mutableStateOf(isDarkTheme) }
                    SettingsSwitchRow(
                        title           = s.settingsDarkTheme,
                        checked         = themeSwitchState,
                        onCheckedChange = { isChecked -> themeSwitchState = isChecked; onToggleTheme() }
                    )
                    PuppetToggle("settings.darkTheme", themeSwitchState) { isChecked ->
                        themeSwitchState = isChecked; onToggleTheme()
                    }
                }

                // ── Behavior ──────────────────────────────────────────────────
                item {
                    SettingsSectionTitle(s.settingsSectionBehavior)
                    SettingsSwitchRow(
                        title           = s.settingsCloseAfterLaunch,
                        checked         = form.closeAfterStart,
                        onCheckedChange = { form.closeAfterStart = it; save() }
                    )
                    PuppetToggle("settings.closeAfterStart", form.closeAfterStart) { form.closeAfterStart = it; save() }

                    Spacer(Modifier.height(16.dp))

                    // Offline Mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (form.isOfflineMode) CelestiaTheme.colors.error.copy(alpha = 0.08f)
                                else CelestiaTheme.colors.background.copy(alpha = 0.4f)
                            )
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.Default.WifiOff,
                                null,
                                tint = if (form.isOfflineMode) CelestiaTheme.colors.error else CelestiaTheme.colors.textSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(
                                    s.settingsOfflineMode,
                                    color = CelestiaTheme.colors.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    s.settingsOfflineModeDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CelestiaTheme.colors.textSecondary
                                )
                            }
                        }
                        Switch(
                            checked = form.isOfflineMode,
                            onCheckedChange = { form.isOfflineMode = it; save() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CelestiaTheme.colors.error,
                                checkedTrackColor = CelestiaTheme.colors.error.copy(alpha = 0.5f)
                            )
                        )
                        PuppetToggle("settings.offlineMode", form.isOfflineMode) { form.isOfflineMode = it; save() }
                    }
                }

                // ── Network ───────────────────────────────────────────────────
                //
                // Single section grouping for "things that affect how Aura
                // talks to the network" (SSL bypasses, proxy toggles, etc.) so
                // users have one place to look.
                item {
                    SettingsSectionTitle(s.settingsSectionNetwork)

                    // Live snapshot -- re-reads every 1s. Sufficient for a
                    // settings screen (no rapid-fire updates expected). Avoids
                    // setting up a Flow purely for this single read site.
                    val bypasses = produceState(initialValue = NetworkState.listBypasses()) {
                        while (true) {
                            value = NetworkState.listBypasses()
                            delay(1_000.milliseconds)
                        }
                    }.value
                    val dateFormatter = DateTimeFormatter
                        .ofLocalizedDateTime(FormatStyle.MEDIUM)
                        .withZone(java.time.ZoneId.systemDefault())

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CelestiaTheme.colors.background.copy(alpha = 0.4f))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text       = s.sslBypassListTitle,
                            color      = CelestiaTheme.colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        if (bypasses.isEmpty()) {
                            Text(
                                text  = s.sslBypassNoEntries,
                                style = MaterialTheme.typography.bodySmall,
                                color = CelestiaTheme.colors.textSecondary,
                            )
                        } else {
                            bypasses.forEach { entry ->
                                Row(
                                    modifier              = Modifier.fillMaxWidth(),
                                    verticalAlignment     = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text       = entry.host,
                                            color      = CelestiaTheme.colors.textPrimary,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text  = s.sslBypassExpiresAt(
                                                dateFormatter.format(java.time.Instant.parse(entry.expiresAt)),
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = CelestiaTheme.colors.textSecondary,
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            ActionRing.record(
                                                "SSL bypass revoked by user from Settings: ${entry.host}",
                                            )
                                            NetworkState.revokeBypass(entry.host)
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                    ) {
                                        Text(s.sslBypassRevoke, color = CelestiaTheme.colors.textSecondary)
                                    }
                                    // Puppet: per-host revoke. Driver picks the host
                                    // by its actual hostname string.
                                    PuppetClick("settings.sslBypass.revoke.${entry.host}") {
                                        ActionRing.record(
                                            "SSL bypass revoked by puppet driver: ${entry.host}",
                                        )
                                        NetworkState.revokeBypass(entry.host)
                                    }
                                }
                            }
                        }

                        // ── Force proxy mode ──────────────────────────────────
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text       = s.settingsForceProxyTitle,
                                    color      = CelestiaTheme.colors.textPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text  = s.settingsForceProxyDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CelestiaTheme.colors.textSecondary,
                                )
                            }
                            Switch(
                                checked         = form.forceProxyMode,
                                onCheckedChange = { form.forceProxyMode = it; save() },
                            )
                        }
                        PuppetToggle("settings.forceProxyMode", form.forceProxyMode) { form.forceProxyMode = it; save() }
                    }
                }

                // ── Experimental features ─────────────────────────────────────
                item {
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

                    Spacer(Modifier.height(16.dp))

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

                    Spacer(Modifier.height(16.dp))

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

                    Spacer(Modifier.height(16.dp))

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

                    Spacer(Modifier.height(16.dp))

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

                    Spacer(Modifier.height(16.dp))

                    // ── Mimic launcher version override ───────────────────────
                    // Toggle row + revealed text input. Doubly gated: master
                    // experimental switch AND the row's own toggle. Saving an
                    // empty/blank text falls back to the shipped default via
                    // the normalisation in save().
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
                        Spacer(Modifier.height(8.dp))
                        // Debounce text-field writes: onValueChange fires per
                        // keystroke and save() runs a synchronous file write
                        // + applies the new value to live protocol traffic.
                        // Calling save() on every keystroke would stutter the
                        // UI on slow disks and push transient partial values
                        // ("3", "3.", "3.6") to the next protocol call before
                        // the user is done. The LaunchedEffect below waits
                        // 400 ms after the last keystroke and then commits.
                        // Toggle-flip persists immediately via its own
                        // onCheckedChange (above) so the dependency between
                        // the toggle and the field stays intuitive.
                        OutlinedTextField(
                            // Filter at every keystroke -- the value propagates
                            // into a User-Agent header, a JVM system property,
                            // and the spawned game's -Dminecraft.launcher.version
                            // argv, all of which reject non-ASCII. A user with
                            // a Cyrillic keyboard layout accidentally typing
                            // here used to break login with an opaque "Network
                            // Error". Protocol.setMimicLauncherVersion repeats
                            // the same check as defense for hand-edited or
                            // older-version persistence files.
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
                            // Skip the initial-composition fire when the
                            // field equals the persisted value.
                            if (form.mimicVersionText == (initialSettings.mimicVersionOverride ?: "")) {
                                return@LaunchedEffect
                            }
                            delay(400.milliseconds)
                            save()
                        }
                    }
                }

                // ── Data directory (move to a different drive / folder) ───────
                //
                // Schedule-on-restart move via DataDirMover. The actual file
                // copy happens on next launcher start (BEFORE PlatformPaths
                // is consulted) so we don't have to fight Windows lock
                // semantics or coordinate with background tasks. The UI here
                // just persists the intent and prompts the user to restart.
                item {
                    SettingsSectionTitle(s.settingsSectionDataDir)

                    var pendingTarget by remember { mutableStateOf<Path?>(null) }
                    var showError     by remember { mutableStateOf<String?>(null) }
                    val moveScope     = rememberCoroutineScope()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CelestiaTheme.colors.background.copy(alpha = 0.4f))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text  = s.settingsDataDirCurrent,
                            style = MaterialTheme.typography.bodySmall,
                            color = CelestiaTheme.colors.textSecondary,
                        )
                        Text(
                            text       = paths.dataDir.toAbsolutePath().toString(),
                            color      = CelestiaTheme.colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            style      = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = {
                                showError = null
                                moveScope.launch {
                                    // filekit uses xdg-desktop-portal on Linux
                                    // (your native Hyprland / KDE / GNOME picker),
                                    // IFileDialog on Windows, NSOpenPanel on macOS.
                                    // No Metal LAF eyesore.
                                    //
                                    // dialogSettings(title=...) is the key bit -- without
                                    // it FileKit hands the portal a blank-title request and
                                    // some backends render a less-styled fallback chrome
                                    // (no titlebar text, generic icon). Match what
                                    // ProfileScreen + ServerSettingsScreen pass here.
                                    val pickedFile = runCatching {
                                        FileKit.openDirectoryPicker(
                                            directory      = PlatformFile(paths.dataDir.toFile()),
                                            dialogSettings = FileKitDialogSettings(title = s.settingsDataDirMove),
                                        )
                                    }.getOrNull() ?: return@launch

                                    val picked = Paths.get(pickedFile.path)

                                    if (picked.toAbsolutePath().normalize() == paths.dataDir.toAbsolutePath().normalize()) {
                                        showError = s.settingsDataDirErrorSamePath
                                        return@launch
                                    }
                                    val populated = withContext(Dispatchers.IO) {
                                        runCatching {
                                            Files.exists(picked) &&
                                                Files.list(picked).use { it.findAny().isPresent }
                                        }.getOrDefault(false)
                                    }
                                    if (populated) {
                                        showError = s.settingsDataDirErrorNotEmpty
                                        return@launch
                                    }
                                    pendingTarget = picked
                                }
                            },
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(s.settingsDataDirMove, color = CelestiaTheme.colors.textPrimary)
                        }
                        if (showError != null) {
                            Text(
                                text  = showError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFEF4444),
                            )
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
                                Button(onClick = {
                                    val ok = DataDirMover.schedule(
                                        source = paths.dataDir,
                                        target = target,
                                    )
                                    if (ok) {
                                        ActionRing.record(
                                            "Data-dir move scheduled: ${paths.dataDir} -> $target -- quitting for restart",
                                        )
                                        // Hard exit -- user explicitly clicked "Quit now". Avoids the
                                        // tray-shutdown path that might re-show the window if a game
                                        // is mid-launch. The pending move only applies AFTER the
                                        // launcher restarts, so a clean process termination is the
                                        // right move.
                                        exitProcess(0)
                                    } else {
                                        // Schedule was refused (target validations failed at the
                                        // mover layer -- e.g., race with another process touching
                                        // the target between our UI check and DataDirMover.schedule).
                                        // Close the dialog so the user can pick a different target.
                                        pendingTarget = null
                                    }
                                }) { Text(s.settingsDataDirQuitNow) }
                            },
                            dismissButton = {
                                OutlinedButton(onClick = { pendingTarget = null }) {
                                    Text(s.sslWarningCancel)
                                }
                            },
                            containerColor = CelestiaTheme.colors.surface,
                        )
                    }
                }

                // ── Diagnostics ───────────────────────────────────────────────
                item {
                    // Secret: tap the diagnostics title 5 times to toggle the April Fools debug panel
                    Box(
                        Modifier.clickable {
                            debugTapCount++
                            if (debugTapCount >= 5) {
                                debugTapCount  = 0
                                showAprilDebug = !showAprilDebug
                            }
                        }
                    ) {
                        SettingsSectionTitle(s.settingsSectionDiagnostics)
                    }

                    // April Fools debug panel (hidden by default)
                    if (showAprilDebug) {
                        Spacer(Modifier.height(8.dp))
                        af.DebugPanel()
                        Spacer(Modifier.height(8.dp))
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Open logs -- chaos target
                        af.ChaosButton(
                            id       = "settings_open_logs_btn",
                            text     = s.settingsOpenLogs,
                            onClick  = { openFolder(paths.logsDir.toString()) },
                            modifier = Modifier.weight(1f),
                            colors   = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor   = CelestiaTheme.colors.textPrimary,
                            ),
                        )
                        PuppetClick("settings.openLogsDir") { openFolder(paths.logsDir.toString()) }
                        // Open crash reports -- chaos target
                        af.ChaosButton(
                            id       = "settings_crash_reports_btn",
                            text     = s.settingsOpenCrashReports,
                            onClick  = { openFolder(paths.crashDir.toString()) },
                            modifier = Modifier.weight(1f),
                            colors   = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor   = CelestiaTheme.colors.textPrimary,
                            ),
                        )
                        PuppetClick("settings.openCrashReports") { openFolder(paths.crashDir.toString()) }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Beacon: one-click ZIP for support -- bundles redacted logs,
                    // crash reports, action ring and system info. The companion
                    // GitHub-Issue button below is enabled only after a bundle
                    // exists in this session.
                    //
                    // Generation runs off the Compose UI thread: filesystem
                    // reads + ZIP compression of a 200 MB launcher.log cap is
                    // enough to freeze Settings for a noticeable beat. While
                    // generating, the button is disabled so a double click
                    // doesn't fire two parallel writes to the same data dir.
                    var lastBundlePath by remember { mutableStateOf<Path?>(null) }
                    var bundleBusy     by remember { mutableStateOf(false) }
                    val bundleScope    = rememberCoroutineScope()

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        af.ChaosButton(
                            id       = "settings_create_diag_bundle_btn",
                            text     = s.settingsCreateDiagnosticBundle,
                            onClick  = {
                                if (bundleBusy) return@ChaosButton
                                bundleBusy = true
                                bundleScope.launch {
                                    val zip = withContext(Dispatchers.IO) {
                                        runCatching { DiagnosticBundle.create(paths) }.getOrNull()
                                    }
                                    if (zip != null) {
                                        lastBundlePath = zip
                                        SystemActions.openFile(zip.parent.toFile())
                                    }
                                    bundleBusy = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors   = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor   = CelestiaTheme.colors.textPrimary.copy(
                                    alpha = if (bundleBusy) 0.45f else 1f
                                ),
                            ),
                            enabled  = !bundleBusy,
                        )
                        PuppetClick("settings.createDiagBundle", enabled = !bundleBusy) {
                            bundleBusy = true
                            bundleScope.launch {
                                val zip = withContext(Dispatchers.IO) {
                                    runCatching { DiagnosticBundle.create(paths) }.getOrNull()
                                }
                                if (zip != null) lastBundlePath = zip
                                bundleBusy = false
                            }
                        }
                        af.ChaosButton(
                            id       = "settings_report_github_btn",
                            text     = s.settingsReportOnGithub,
                            onClick  = {
                                val zip = lastBundlePath ?: return@ChaosButton
                                runCatching {
                                    // Copy path so the user can drag-attach from the file
                                    // manager OR paste the path into a comment.
                                    Toolkit.getDefaultToolkit().systemClipboard
                                        .setContents(StringSelection(zip.toString()), null)
                                    SystemActions.openUrl(IssueReporter.bundleIssueUrl(zip))
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors   = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor   = CelestiaTheme.colors.textPrimary.copy(
                                    alpha = if (lastBundlePath != null) 1f else 0.35f
                                ),
                            ),
                            enabled  = lastBundlePath != null,
                        )
                        PuppetClick("settings.reportOnGithub", enabled = lastBundlePath != null) {
                            val zip = lastBundlePath ?: return@PuppetClick
                            runCatching {
                                Toolkit.getDefaultToolkit().systemClipboard
                                    .setContents(StringSelection(zip.toString()), null)
                                SystemActions.openUrl(IssueReporter.bundleIssueUrl(zip))
                            }
                        }
                    }
                    Text(
                        text     = s.settingsDiagnosticBundleHint,
                        color    = CelestiaTheme.colors.textSecondary,
                        fontSize = TextUnit(11f, TextUnitType.Sp),
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                    )
                }

                // ── About ─────────────────────────────────────────────────────
                item {
                    SettingsSectionTitle(s.settingsSectionAbout)
                    PuppetClick("settings.openAbout") { onOpenAbout() }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onOpenAbout)
                            .background(CelestiaTheme.colors.surface.copy(alpha = 0.4f))
                            .padding(16.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, tint = CelestiaTheme.colors.primary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(Branding.TITLE, color = CelestiaTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                                Text(
                                    "v${Branding.VERSION.removePrefix("v")} -- GPLv3",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CelestiaTheme.colors.textSecondary
                                )
                            }
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = CelestiaTheme.colors.textSecondary)
                    }
                }
            }
        }

        if (showSavedMessage) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(s.settingsSaved, color = CelestiaTheme.colors.success, style = MaterialTheme.typography.bodySmall)
            }
            LaunchedEffect(showSavedMessage) {
                if (showSavedMessage) { delay(2000.milliseconds); showSavedMessage = false }
            }
        }
    }
}

/**
 * Mutable form-state holder for the editable surface of [SettingsScreen].
 *
 * One field per editable setting; each field is its own [mutableStateOf]
 * so Compose recomposition stays granular (flipping a single toggle only
 * invalidates the rows that read that one field). The class is a thin
 * namespace -- it does not centralize behavior, only the ten or so
 * `var x by mutableStateOf(initial.x)` declarations that otherwise sit
 * inline in the composable.
 *
 * [mimicOverrideEnabled] / [mimicVersionText] are UI-only state, not
 * 1:1-mapped to [SettingsData]:
 *   - `mimicOverrideEnabled` is derived from whether the persisted
 *     override is non-blank at composition time, then maintained
 *     independently so a user can toggle off, edit the text, toggle on
 *     without losing what they typed.
 *   - `mimicVersionText` is the live edit value; `mergeInto` normalises
 *     it (trim + blank-to-null) before writing to [SettingsData].
 */
@Stable
private class SettingsFormState(initial: SettingsData) {
    var closeAfterStart        by mutableStateOf(initial.closeAfterStart)
    var isOfflineMode          by mutableStateOf(initial.isOfflineMode)
    var experimentalEnabled    by mutableStateOf(initial.experimentalFeaturesEnabled)
    var mandatoryUpdates       by mutableStateOf(initial.mandatoryUpdatesEnabled)
    var prereleaseChannel      by mutableStateOf(initial.prereleaseChannelEnabled)
    var autoSyncAllPacks       by mutableStateOf(initial.autoSyncAllPacks)
    var jvmBuilderEnabled      by mutableStateOf(initial.jvmBuilderEnabled)
    var forceProxyMode         by mutableStateOf(initial.forceProxyMode)
    var mimicOverrideEnabled   by mutableStateOf(!initial.mimicVersionOverride.isNullOrBlank())
    var mimicVersionText       by mutableStateOf(initial.mimicVersionOverride ?: "")

    /**
     * Build a [SettingsData] suitable for persistence by overlaying this
     * form's editable fields onto [current] (a freshly-read snapshot, so
     * non-screen fields like server-specific knobs are not clobbered).
     *
     * The mimic-version override is normalized here: empty toggle OR
     * blank text both collapse to null, which is the contract
     * [SettingsData.mimicVersionOverride] expects for "use the shipped
     * default" semantics. Storing a stale non-null value with the toggle
     * off would silently re-arm on next launch via the Main.kt restore
     * block.
     */
    fun mergeInto(current: SettingsData): SettingsData {
        val normalisedMimic = if (mimicOverrideEnabled) mimicVersionText.trim().ifBlank { null } else null
        return current.copy(
            closeAfterStart             = closeAfterStart,
            isOfflineMode               = isOfflineMode,
            experimentalFeaturesEnabled = experimentalEnabled,
            mandatoryUpdatesEnabled     = mandatoryUpdates,
            prereleaseChannelEnabled    = prereleaseChannel,
            autoSyncAllPacks            = autoSyncAllPacks,
            jvmBuilderEnabled           = jvmBuilderEnabled,
            forceProxyMode              = forceProxyMode,
            mimicVersionOverride        = normalisedMimic,
        )
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text.uppercase(),
        style      = MaterialTheme.typography.bodySmall,
        color      = CelestiaTheme.colors.primary,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = CelestiaTheme.colors.primary.copy(alpha = 0.3f))
    Spacer(Modifier.height(16.dp))
}

/**
 * Settings row with icon + title + description + switch. Mirrors the layout
 * used by the Offline Mode and Start-in-Tray rows above. [enabled] grays out
 * the whole row (used by experimental sub-toggles when the master is off).
 */
@Composable
private fun SettingsRowWithDescription(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CelestiaTheme.colors.background.copy(alpha = 0.4f))
            .padding(16.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                icon, null,
                tint     = iconTint.copy(alpha = alpha),
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    title,
                    color      = CelestiaTheme.colors.textPrimary.copy(alpha = alpha),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary.copy(alpha = alpha)
                )
            }
        }
        Switch(
            checked         = checked,
            enabled         = enabled,
            onCheckedChange = onCheckedChange,
            colors          = SwitchDefaults.colors(
                checkedThumbColor = CelestiaTheme.colors.primary,
                checkedTrackColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
private fun SettingsSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = CelestiaTheme.colors.textPrimary)
        Switch(
            checked         = checked,
            onCheckedChange = onCheckedChange,
            colors          = SwitchDefaults.colors(
                checkedThumbColor  = CelestiaTheme.colors.primary,
                checkedTrackColor  = CelestiaTheme.colors.primary.copy(alpha = 0.5f)
            )
        )
    }
}
