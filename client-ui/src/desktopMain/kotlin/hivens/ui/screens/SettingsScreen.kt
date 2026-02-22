package hivens.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.SeasonTheme
import hivens.ui.components.GlassCard
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import org.koin.compose.koinInject

@Composable
fun SettingsScreen(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onThemeChanged: (SeasonTheme) -> Unit,
    onOpenThemePicker: () -> Unit,
    currentLocale: AppLocale,
    onLocaleChanged: (AppLocale) -> Unit
) {
    val settingsService: ISettingsService = koinInject()
    val s = LocalStrings.current

    val initialSettings = remember { settingsService.getSettings() }
    var closeAfterStart by remember { mutableStateOf(initialSettings.closeAfterStart) }
    var selectedTheme by remember { mutableStateOf(initialSettings.seasonalTheme) }
    var isThemeDropdownExpanded by remember { mutableStateOf(false) }
    var isLangDropdownExpanded by remember { mutableStateOf(false) }
    var showSavedMessage by remember { mutableStateOf(false) }

    fun save() {
        val current = settingsService.getSettings()
        settingsService.saveSettings(
            current.copy(
                closeAfterStart = closeAfterStart,
                seasonalTheme = selectedTheme
                // locale is saved in Main.kt via onLocaleChanged
            )
        )
        showSavedMessage = true
    }

    // Helper: localised season name
    fun seasonName(theme: SeasonTheme) = when (theme) {
        SeasonTheme.AUTO     -> s.seasonAuto
        SeasonTheme.NONE     -> s.seasonNone
        SeasonTheme.WINTER   -> s.seasonWinter
        SeasonTheme.NEW_YEAR -> s.seasonNewYear
        SeasonTheme.SPRING   -> s.seasonSpring
        SeasonTheme.SUMMER   -> s.seasonSummer
        SeasonTheme.AUTUMN   -> s.seasonAutumn
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = s.settingsTitle,
            style = MaterialTheme.typography.h5,
            color = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(24.dp))

        GlassCard(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {

                // ── Interface section ────────────────────────────────────────
                item {
                    SettingsSectionTitle(s.settingsSectionUI)

                    // Language picker
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CelestiaTheme.colors.background.copy(alpha = 0.4f))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Language,
                                contentDescription = null,
                                tint = CelestiaTheme.colors.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(s.settingsLanguage, color = CelestiaTheme.colors.textPrimary)
                        }

                        Box {
                            Row(
                                Modifier.clickable { isLangDropdownExpanded = true }.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(currentLocale.displayName, color = CelestiaTheme.colors.primary, fontWeight = FontWeight.Bold)
                                Icon(Icons.Default.ArrowDropDown, null, tint = CelestiaTheme.colors.primary)
                            }

                            DropdownMenu(
                                expanded = isLangDropdownExpanded,
                                onDismissRequest = { isLangDropdownExpanded = false },
                                modifier = Modifier.background(CelestiaTheme.colors.surface)
                            ) {
                                AppLocale.entries.forEach { locale ->
                                    DropdownMenuItem(
                                        onClick = {
                                            isLangDropdownExpanded = false
                                            onLocaleChanged(locale)
                                        }
                                    ) {
                                        Text(
                                            locale.displayName,
                                            color = if (locale == currentLocale)
                                                CelestiaTheme.colors.primary
                                            else
                                                CelestiaTheme.colors.textPrimary,
                                            fontWeight = if (locale == currentLocale) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = CelestiaTheme.colors.primary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(s.settingsThemePicker, color = CelestiaTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                                Text(s.settingsThemePickerSub, style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.textSecondary)
                            }
                        }
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = CelestiaTheme.colors.primary)
                    }

                    Spacer(Modifier.height(16.dp))

                    // Dark theme toggle
                    var themeSwitchState by remember(isDarkTheme) { mutableStateOf(isDarkTheme) }
                    SettingsSwitchRow(
                        title = s.settingsDarkTheme,
                        checked = themeSwitchState,
                        onCheckedChange = { isChecked ->
                            themeSwitchState = isChecked
                            onToggleTheme()
                        }
                    )

                    Spacer(Modifier.height(16.dp))

                    // Seasonal effect
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(s.settingsSeasonEffect, color = CelestiaTheme.colors.textPrimary)
                            Text(s.settingsSeasonEffectSub, style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.textSecondary)
                        }
                        Box {
                            Row(
                                Modifier.clickable { isThemeDropdownExpanded = true }.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(seasonName(selectedTheme), color = CelestiaTheme.colors.primary, fontWeight = FontWeight.Bold)
                                Icon(Icons.Default.ArrowDropDown, null, tint = CelestiaTheme.colors.primary)
                            }
                            DropdownMenu(
                                expanded = isThemeDropdownExpanded,
                                onDismissRequest = { isThemeDropdownExpanded = false },
                                modifier = Modifier.background(CelestiaTheme.colors.surface)
                            ) {
                                SeasonTheme.entries.forEach { theme ->
                                    DropdownMenuItem(onClick = {
                                        selectedTheme = theme
                                        isThemeDropdownExpanded = false
                                        onThemeChanged(theme)
                                        save()
                                    }) {
                                        Text(seasonName(theme), color = CelestiaTheme.colors.textPrimary)
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Behavior section ─────────────────────────────────────────
                item {
                    SettingsSectionTitle(s.settingsSectionBehavior)
                    SettingsSwitchRow(
                        title = s.settingsCloseAfterLaunch,
                        checked = closeAfterStart,
                        onCheckedChange = { closeAfterStart = it; save() }
                    )
                }
            }
        }

        if (showSavedMessage) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(s.settingsSaved, color = CelestiaTheme.colors.success, style = MaterialTheme.typography.caption)
            }
            LaunchedEffect(showSavedMessage) {
                if (showSavedMessage) {
                    kotlinx.coroutines.delay(2000)
                    showSavedMessage = false
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(text = text.uppercase(), style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.primary, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Divider(color = CelestiaTheme.colors.primary.copy(alpha = 0.3f))
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SettingsSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.body1, color = CelestiaTheme.colors.textPrimary)
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CelestiaTheme.colors.primary,
                checkedTrackColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f)
            )
        )
    }
}
