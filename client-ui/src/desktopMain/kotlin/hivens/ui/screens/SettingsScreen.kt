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
import hivens.launcher.platform.PlatformPaths
import hivens.ui.components.GlassCard
import hivens.ui.easter.AprilFoolsButton
import hivens.ui.easter.AprilFoolsDebugPanel
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import org.koin.compose.koinInject
import java.awt.Desktop
import java.io.File

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
    val settingsService: ISettingsService = koinInject()
    val paths: PlatformPaths              = koinInject()
    val s = LocalStrings.current

    val initialSettings        = remember { settingsService.getSettings() }
    var closeAfterStart        by remember { mutableStateOf(initialSettings.closeAfterStart) }
    var isOfflineMode          by remember { mutableStateOf(initialSettings.isOfflineMode) }
    var startInTray            by remember { mutableStateOf(initialSettings.startInTray) }
    var experimentalEnabled    by remember { mutableStateOf(initialSettings.experimentalFeaturesEnabled) }
    var mandatoryUpdates       by remember { mutableStateOf(initialSettings.mandatoryUpdatesEnabled) }
    var prereleaseChannel      by remember { mutableStateOf(initialSettings.prereleaseChannelEnabled) }
    var autoSyncAllPacks       by remember { mutableStateOf(initialSettings.autoSyncAllPacks) }
    var jvmBuilderEnabled      by remember { mutableStateOf(initialSettings.jvmBuilderEnabled) }
    var langDropdownExpanded   by remember { mutableStateOf(false) }
    var showSavedMessage       by remember { mutableStateOf(false) }

    // ── April Fools debug panel — secret unlock ────────────────────────────────
    // Tap the DIAGNOSTICS section title 5 times to reveal the debug panel.
    var debugTapCount  by remember { mutableStateOf(0) }
    var showAprilDebug by remember { mutableStateOf(false) }

    fun save() {
        val current = settingsService.getSettings()
        settingsService.saveSettings(
            current.copy(
                closeAfterStart             = closeAfterStart,
                isOfflineMode               = isOfflineMode,
                startInTray                 = startInTray,
                experimentalFeaturesEnabled = experimentalEnabled,
                mandatoryUpdatesEnabled     = mandatoryUpdates,
                prereleaseChannelEnabled    = prereleaseChannel,
                autoSyncAllPacks            = autoSyncAllPacks,
                jvmBuilderEnabled           = jvmBuilderEnabled
            )
        )
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
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Theme picker shortcut
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
                }

                // ── Behavior ──────────────────────────────────────────────────
                item {
                    SettingsSectionTitle(s.settingsSectionBehavior)
                    SettingsSwitchRow(
                        title           = s.settingsCloseAfterLaunch,
                        checked         = closeAfterStart,
                        onCheckedChange = { closeAfterStart = it; save() }
                    )

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
                    }
                    Spacer(Modifier.height(16.dp))

                    // System Tray
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CelestiaTheme.colors.background.copy(alpha = 0.4f))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Minimize,
                                null,
                                tint = CelestiaTheme.colors.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(
                                    s.settingsStartInTray,
                                    color = CelestiaTheme.colors.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    s.settingsStartInTrayDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CelestiaTheme.colors.textSecondary
                                )
                            }
                        }
                        Switch(
                            checked = startInTray,
                            onCheckedChange = { startInTray = it; save() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CelestiaTheme.colors.primary,
                                checkedTrackColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f)
                            )
                        )
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
                        // Open logs — chaos target
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
                        // Open crash reports — chaos target
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
                    }
                }

                // ── About ─────────────────────────────────────────────────────
                item {
                    SettingsSectionTitle(s.settingsSectionAbout)

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
                                    "v${Branding.VERSION.removePrefix("v")} — GPLv3",
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
                if (showSavedMessage) { kotlinx.coroutines.delay(2000); showSavedMessage = false }
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
 * used by the Offline Mode and Start-in-Tray rows above. [enabled] greys out
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
