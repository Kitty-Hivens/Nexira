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
import hivens.config.AppConfig
import hivens.core.api.interfaces.ISettingsService
import hivens.ui.components.CelestiaButton
import hivens.ui.components.GlassCard
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
    val s = LocalStrings.current

    val initialSettings        = remember { settingsService.getSettings() }
    var closeAfterStart        by remember { mutableStateOf(initialSettings.closeAfterStart) }
    var isOfflineMode          by remember { mutableStateOf(initialSettings.isOfflineMode) }
    var startInTray            by remember { mutableStateOf(initialSettings.startInTray) }
    var langDropdownExpanded   by remember { mutableStateOf(false) }
    var showSavedMessage       by remember { mutableStateOf(false) }

    fun save() {
        val current = settingsService.getSettings()
        settingsService.saveSettings(
            current.copy(
                closeAfterStart = closeAfterStart,
                isOfflineMode   = isOfflineMode,
                startInTray     = startInTray
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

                // ── Diagnostics ───────────────────────────────────────────────
                item {
                    SettingsSectionTitle(s.settingsSectionDiagnostics)

                    val userHome = System.getProperty("user.home")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CelestiaButton(
                            s.settingsOpenLogs,
                            onClick  = { openFolder("logs") },
                            modifier = Modifier.weight(1f),
                            primary  = false
                        )
                        CelestiaButton(
                            s.settingsOpenCrashReports,
                            onClick  = { openFolder("$userHome/.aura/crash-reports") },
                            modifier = Modifier.weight(1f),
                            primary  = false
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
                                Text(AppConfig.APP_TITLE, color = CelestiaTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                                Text(
                                    "v${AppConfig.CLIENT_VERSION.removePrefix("v")} — GPLv3",
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
