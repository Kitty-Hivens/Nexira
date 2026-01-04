package hivens.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.SeasonTheme
import hivens.ui.components.GlassCard
import hivens.ui.theme.CelestiaTheme
import org.koin.compose.koinInject

@Composable
fun SettingsScreen(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onThemeChanged: (SeasonTheme) -> Unit
) {
    val settingsService: ISettingsService = koinInject()

    // Загружаем настройки при старте
    // Используем remember, чтобы не перечитывать конфиг при каждом рекомпозишене
    val initialSettings = remember { settingsService.getSettings() }

    var closeAfterStart by remember { mutableStateOf(initialSettings.closeAfterStart) }
    var selectedTheme by remember { mutableStateOf(initialSettings.seasonalTheme) }

    // Состояние выпадающего списка и сообщения о сохранении
    var isThemeDropdownExpanded by remember { mutableStateOf(false) }
    var showSavedMessage by remember { mutableStateOf(false) }

    // Функция сохранения
    fun save() {
        // Получаем актуальный объект (на случай, если он изменился извне, хотя здесь это редкость)
        val current = settingsService.getSettings()

        // Копируем с новыми значениями
        val newSettings = current.copy(
            closeAfterStart = closeAfterStart,
            seasonalTheme = selectedTheme
        )

        // Сохраняем
        settingsService.saveSettings(newSettings)
        showSavedMessage = true
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "ГЛОБАЛЬНЫЕ НАСТРОЙКИ",
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
                // --- Секция: Интерфейс ---
                item {
                    SettingsSectionTitle("Интерфейс")

                    // Темная тема
                    var themeSwitchState by remember(isDarkTheme) { mutableStateOf(isDarkTheme) }
                    SettingsSwitchRow(
                        title = "Темная тема",
                        checked = themeSwitchState, // Используем локальную переменную
                        onCheckedChange = { isChecked ->
                            themeSwitchState = isChecked // 1. Мгновенно двигаем ползунок
                            onToggleTheme()              // 2. Запускаем тяжелую смену темы
                        }
                    )

                    Spacer(Modifier.height(16.dp))

                    // Сезонный эффект (Выпадающий список)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Сезонный эффект", color = CelestiaTheme.colors.textPrimary)
                            Text("Анимация на заднем фоне", style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.textSecondary)
                        }

                        Box {
                            Row(
                                Modifier
                                    .clickable { isThemeDropdownExpanded = true }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(selectedTheme.title, color = CelestiaTheme.colors.primary, fontWeight = FontWeight.Bold)
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
                                        save() // Сохраняем сразу при выборе
                                    }) {
                                        Text(theme.title, color = CelestiaTheme.colors.textPrimary)
                                    }
                                }
                            }
                        }
                    }
                }

                // --- Секция: Поведение ---
                item {
                    SettingsSectionTitle("Поведение")

                    SettingsSwitchRow(
                        title = "Закрывать лаунчер после запуска игры",
                        checked = closeAfterStart,
                        onCheckedChange = {
                            closeAfterStart = it
                            save() // Сохраняем сразу
                        }
                    )
                }

                // Секция памяти удалена, как и просили
            }
        }

        // Footer с сообщением, если нужно (хотя мы сохраняем сразу, но оставим для обратной связи)
        if (showSavedMessage) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("Настройки сохранены", color = CelestiaTheme.colors.success, style = MaterialTheme.typography.caption)
            }
            // Сбрасываем сообщение через 2 секунды
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
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.caption,
        color = CelestiaTheme.colors.primary,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))
    Divider(color = CelestiaTheme.colors.primary.copy(alpha = 0.3f))
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.body1, color = CelestiaTheme.colors.textPrimary)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CelestiaTheme.colors.primary,
                checkedTrackColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f)
            )
        )
    }
}
