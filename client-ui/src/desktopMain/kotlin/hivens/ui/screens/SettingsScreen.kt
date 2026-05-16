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
import hivens.config.Branding
import hivens.core.api.interfaces.ISettingsService
import hivens.launcher.diag.DiagnosticBundle
import hivens.launcher.platform.PlatformPaths
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import hivens.ui.components.GlassCard
import hivens.ui.easter.AprilFoolsButton
import hivens.ui.easter.AprilFoolsDebugPanel
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.puppet.PuppetToggle
import hivens.ui.theme.CelestiaTheme
import org.koin.compose.koinInject
import java.awt.Desktop
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

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

    val initialSettings        = remember { settingsService.getSettings() }
    var closeAfterStart        by remember { mutableStateOf(initialSettings.closeAfterStart) } // TODO: Duplicate
    var isOfflineMode          by remember { mutableStateOf(initialSettings.isOfflineMode) }
    var experimentalEnabled    by remember { mutableStateOf(initialSettings.experimentalFeaturesEnabled) }
    var mandatoryUpdates       by remember { mutableStateOf(initialSettings.mandatoryUpdatesEnabled) }
    var prereleaseChannel      by remember { mutableStateOf(initialSettings.prereleaseChannelEnabled) } // TODO: Duplicate
    var autoSyncAllPacks       by remember { mutableStateOf(initialSettings.autoSyncAllPacks) }
    var jvmBuilderEnabled      by remember { mutableStateOf(initialSettings.jvmBuilderEnabled) }
    var forceProxyMode         by remember { mutableStateOf(initialSettings.forceProxyMode) }
    var langDropdownExpanded   by remember { mutableStateOf(false) }
    var showSavedMessage       by remember { mutableStateOf(false) }

    // ── April Fools debug panel -- secret unlock ────────────────────────────────
    // Tap the DIAGNOSTICS section title 5 times to reveal the debug panel.
    var debugTapCount  by remember { mutableStateOf(0) }
    var showAprilDebug by remember { mutableStateOf(false) }

    fun save() {
        val current = settingsService.getSettings()
        settingsService.saveSettings(
            current.copy(
                closeAfterStart             = closeAfterStart,
                isOfflineMode               = isOfflineMode,
                experimentalFeaturesEnabled = experimentalEnabled,
                mandatoryUpdatesEnabled     = mandatoryUpdates,
                prereleaseChannelEnabled    = prereleaseChannel,
                autoSyncAllPacks            = autoSyncAllPacks,
                jvmBuilderEnabled           = jvmBuilderEnabled,
                forceProxyMode              = forceProxyMode
            )
        )
        // Mirror to NetworkState so ChannelRouter sees it on the very next
        // request without waiting for launcher restart.
        hivens.launcher.NetworkState.setForceProxyMode(forceProxyMode)
        showSavedMessage = true
    }

    fun openFolder(path: String) {
        val dir = File(path).also { it.mkdirs() }
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(dir)
    }

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
                        checked         = closeAfterStart,
                        onCheckedChange = { closeAfterStart = it; save() }
                    )
                    PuppetToggle("settings.closeAfterStart", closeAfterStart) { closeAfterStart = it; save() }

                    Spacer(Modifier.height(16.dp))

                    // Offline Mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isOfflineMode) CelestiaTheme.colors.error.copy(alpha = 0.08f)
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
                                tint = if (isOfflineMode) CelestiaTheme.colors.error else CelestiaTheme.colors.textSecondary,
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
                            checked = isOfflineMode,
                            onCheckedChange = { isOfflineMode = it; save() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CelestiaTheme.colors.error,
                                checkedTrackColor = CelestiaTheme.colors.error.copy(alpha = 0.5f)
                            )
                        )
                        PuppetToggle("settings.offlineMode", isOfflineMode) { isOfflineMode = it; save() }
                    }
                }

                // ── Network ───────────────────────────────────────────────────
                //
                // Currently surfaces just the SSL-bypass list (Vault #2 followup).
                // Future: proxy override, channel routing toggles, etc. live
                // here under one section so users have a single place to look
                // for "things that affect how Aura talks to the network".
                item {
                    SettingsSectionTitle(s.settingsSectionNetwork)

                    // Live snapshot -- re-reads every 1s. Sufficient for a
                    // settings screen (no rapid-fire updates expected). Avoids
                    // setting up a Flow purely for this single read site.
                    val bypasses = androidx.compose.runtime.produceState(initialValue = hivens.launcher.NetworkState.listBypasses()) {
                        while (true) {
                            value = hivens.launcher.NetworkState.listBypasses()
                            kotlinx.coroutines.delay(1_000.milliseconds)
                        }
                    }.value
                    val dateFormatter = java.time.format.DateTimeFormatter
                        .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
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
                                            hivens.core.diag.ActionRing.record(
                                                "SSL bypass revoked by user from Settings: ${entry.host}",
                                            )
                                            hivens.launcher.NetworkState.revokeBypass(entry.host)
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                    ) {
                                        Text(s.sslBypassRevoke, color = CelestiaTheme.colors.textSecondary)
                                    }
                                    // Puppet: per-host revoke. Driver picks the host
                                    // by its actual hostname string.
                                    PuppetClick("settings.sslBypass.revoke.${entry.host}") {
                                        hivens.core.diag.ActionRing.record(
                                            "SSL bypass revoked by puppet driver: ${entry.host}",
                                        )
                                        hivens.launcher.NetworkState.revokeBypass(entry.host)
                                    }
                                }
                            }
                        }

                        // ── Force proxy mode (Conduit Phase 2) ────────────────
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
                                checked         = forceProxyMode,
                                onCheckedChange = { forceProxyMode = it; save() },
                            )
                        }
                        PuppetToggle("settings.forceProxyMode", forceProxyMode) { forceProxyMode = it; save() }
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
                        checked        = experimentalEnabled,
                        enabled        = true,
                        onCheckedChange = { experimentalEnabled = it; save() }
                    )
                    PuppetToggle("settings.experimental", experimentalEnabled) { experimentalEnabled = it; save() }

                    Spacer(Modifier.height(16.dp))

                    SettingsRowWithDescription(
                        title          = s.settingsMandatoryUpdates,
                        description    = s.settingsMandatoryUpdatesDesc,
                        icon           = Icons.Default.Update,
                        iconTint       = CelestiaTheme.colors.primary,
                        checked        = experimentalEnabled && mandatoryUpdates,
                        enabled        = experimentalEnabled,
                        onCheckedChange = { mandatoryUpdates = it; save() }
                    )
                    // Mirror the UI's enabled-gating: master switch off => can't touch sub-toggles.
                    PuppetToggle("settings.mandatoryUpdates", mandatoryUpdates, enabled = experimentalEnabled) {
                        mandatoryUpdates = it; save()
                    }

                    Spacer(Modifier.height(16.dp))

                    SettingsRowWithDescription(
                        title          = s.settingsPrereleaseChannel,
                        description    = s.settingsPrereleaseChannelDesc,
                        icon           = Icons.Default.NewReleases,
                        iconTint       = CelestiaTheme.colors.primary,
                        checked        = experimentalEnabled && prereleaseChannel,
                        enabled        = experimentalEnabled,
                        onCheckedChange = { prereleaseChannel = it; save() }
                    )
                    PuppetToggle("settings.prereleaseChannel", prereleaseChannel, enabled = experimentalEnabled) {
                        prereleaseChannel = it; save()
                    }

                    Spacer(Modifier.height(16.dp))

                    SettingsRowWithDescription(
                        title          = s.settingsAutoSyncAllPacks,
                        description    = s.settingsAutoSyncAllPacksDesc,
                        icon           = Icons.Default.Sync,
                        iconTint       = CelestiaTheme.colors.primary,
                        checked        = experimentalEnabled && autoSyncAllPacks,
                        enabled        = experimentalEnabled,
                        onCheckedChange = { autoSyncAllPacks = it; save() }
                    )
                    PuppetToggle("settings.autoSyncAllPacks", autoSyncAllPacks, enabled = experimentalEnabled) {
                        autoSyncAllPacks = it; save()
                    }

                    Spacer(Modifier.height(16.dp))

                    SettingsRowWithDescription(
                        title          = s.settingsJvmBuilder,
                        description    = s.settingsJvmBuilderDesc,
                        icon           = Icons.Default.Tune,
                        iconTint       = CelestiaTheme.colors.primary,
                        checked        = experimentalEnabled && jvmBuilderEnabled,
                        enabled        = experimentalEnabled,
                        onCheckedChange = { jvmBuilderEnabled = it; save() }
                    )
                    PuppetToggle("settings.jvmBuilder", jvmBuilderEnabled, enabled = experimentalEnabled) {
                        jvmBuilderEnabled = it; save()
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

                    var pendingTarget by remember { mutableStateOf<java.nio.file.Path?>(null) }
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
                                    val pickedFile = runCatching {
                                        FileKit.openDirectoryPicker(
                                            directory = PlatformFile(paths.dataDir.toFile()),
                                        )
                                    }.getOrNull() ?: return@launch

                                    val picked = java.nio.file.Paths.get(pickedFile.path)

                                    if (picked.toAbsolutePath().normalize() == paths.dataDir.toAbsolutePath().normalize()) {
                                        showError = s.settingsDataDirErrorSamePath
                                        return@launch
                                    }
                                    val populated = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        runCatching {
                                            java.nio.file.Files.exists(picked) &&
                                                java.nio.file.Files.list(picked).use { it.findAny().isPresent }
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
                                    val ok = hivens.launcher.platform.DataDirMover.schedule(
                                        source = paths.dataDir,
                                        target = target,
                                    )
                                    if (ok) {
                                        hivens.core.diag.ActionRing.record(
                                            "Data-dir move scheduled: ${paths.dataDir} -> $target -- quitting for restart",
                                        )
                                        // Hard exit -- user explicitly clicked "Quit now". Avoids the
                                        // tray-shutdown path that might re-show the window if a game
                                        // is mid-launch. The pending move only applies AFTER the
                                        // launcher restarts, so a clean process termination is the
                                        // right move.
                                        kotlin.system.exitProcess(0)
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
                        AprilFoolsDebugPanel()
                        Spacer(Modifier.height(8.dp))
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Open logs -- chaos target
                        AprilFoolsButton(
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
                        AprilFoolsButton(
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
                    var lastBundlePath by remember { mutableStateOf<java.nio.file.Path?>(null) }
                    var bundleBusy     by remember { mutableStateOf(false) }
                    val bundleScope    = rememberCoroutineScope()

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AprilFoolsButton(
                            id       = "settings_create_diag_bundle_btn",
                            text     = s.settingsCreateDiagnosticBundle,
                            onClick  = {
                                if (bundleBusy) return@AprilFoolsButton
                                bundleBusy = true
                                bundleScope.launch {
                                    val zip = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        runCatching { DiagnosticBundle.create(paths) }.getOrNull()
                                    }
                                    if (zip != null) {
                                        lastBundlePath = zip
                                        if (Desktop.isDesktopSupported()) {
                                            runCatching { Desktop.getDesktop().open(zip.parent.toFile()) }
                                        }
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
                                val zip = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching { DiagnosticBundle.create(paths) }.getOrNull()
                                }
                                if (zip != null) lastBundlePath = zip
                                bundleBusy = false
                            }
                        }
                        AprilFoolsButton(
                            id       = "settings_report_github_btn",
                            text     = s.settingsReportOnGithub,
                            onClick  = {
                                val zip = lastBundlePath ?: return@AprilFoolsButton
                                runCatching {
                                    // Copy path so the user can drag-attach from the file
                                    // manager OR paste the path into a comment.
                                    java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                        .setContents(java.awt.datatransfer.StringSelection(zip.toString()), null)
                                    if (Desktop.isDesktopSupported()) {
                                        Desktop.getDesktop().browse(
                                            java.net.URI(hivens.launcher.diag.IssueReporter.bundleIssueUrl(zip))
                                        )
                                    }
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
                                java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                    .setContents(java.awt.datatransfer.StringSelection(zip.toString()), null)
                                if (Desktop.isDesktopSupported()) {
                                    Desktop.getDesktop().browse(
                                        java.net.URI(hivens.launcher.diag.IssueReporter.bundleIssueUrl(zip))
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text     = s.settingsDiagnosticBundleHint,
                        color    = CelestiaTheme.colors.textSecondary,
                        fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
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
                if (showSavedMessage) { kotlinx.coroutines.delay(2000.milliseconds); showSavedMessage = false }
            }
        }
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
