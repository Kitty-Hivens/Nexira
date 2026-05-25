package hivens.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.config.ExperimentalProtocolOverride
import hivens.config.Protocol
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.HomeView
import hivens.core.data.UiStyle
import hivens.launcher.network.NetworkState
import hivens.launcher.platform.PlatformPaths
import hivens.ui.components.GlassCard
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Settings orchestrator: form state, persistence, the "saved" banner,
 * and the [SettingsCategory] routing of the right pane. Sections live
 * in their per-domain files in this package.
 *
 * save() mirrors [NetworkState.forceProxyMode] and
 * [Protocol.setMimicLauncherVersion] inline -- those reads are
 * per-protocol-call, so without the mirror the user would need a
 * restart for those two knobs to take effect.
 */
@Composable
fun SettingsScreen(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenThemePicker: () -> Unit,
    currentLocale: AppLocale,
    onLocaleChanged: (AppLocale) -> Unit,
    homeView: HomeView,
    onHomeViewChanged: (HomeView) -> Unit,
    uiStyle: UiStyle,
    onUiStyleChanged: (UiStyle) -> Unit,
    onOpenBackgroundSettings: () -> Unit = {},
    onOpenCustomizationExtension: () -> Unit = {},
    onOpenAbout: () -> Unit = {}
) {
    PuppetScreen("Settings")

    val settingsService: ISettingsService = koinInject()
    val paths: PlatformPaths              = koinInject()
    val s = LocalStrings.current

    val initialSettings = remember { settingsService.getSettings() }
    val form            = remember { SettingsFormState(initialSettings) }
    var showSavedMessage by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(SettingsCategory.Appearance) }

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

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text       = s.settingsTitle,
            style      = MaterialTheme.typography.headlineSmall,
            color      = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(24.dp))

        // Explicit backgroundColor opts the Settings frame out of
        // style.cardSurface -- stays glassy under Brut, same as the
        // inner row panels.
        GlassCard(
            modifier        = Modifier.weight(1f).fillMaxWidth(),
            backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        ) {
            Row(Modifier.fillMaxSize().padding(16.dp)) {
                SettingsCategoryNav(
                    current  = selectedCategory,
                    onSelect = { selectedCategory = it },
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (selectedCategory) {
                        SettingsCategory.Appearance -> AppearanceSection(
                            form                         = form,
                            save                         = ::save,
                            isDarkTheme                  = isDarkTheme,
                            onToggleTheme                = onToggleTheme,
                            onOpenThemePicker            = onOpenThemePicker,
                            onOpenBackgroundSettings     = onOpenBackgroundSettings,
                            onOpenCustomizationExtension = onOpenCustomizationExtension,
                            currentLocale                = currentLocale,
                            onLocaleChanged              = onLocaleChanged,
                            homeView                     = homeView,
                            onHomeViewChanged            = onHomeViewChanged,
                            uiStyle                      = uiStyle,
                            onUiStyleChanged             = onUiStyleChanged,
                        )
                        SettingsCategory.Network -> NetworkSection(
                            form = form,
                            save = ::save,
                        )
                        SettingsCategory.Experimental -> ExperimentalSection(
                            form            = form,
                            save            = ::save,
                            initialSettings = initialSettings,
                        )
                        SettingsCategory.Advanced -> AdvancedSection(paths = paths)
                        SettingsCategory.Diagnostics -> DiagnosticsSection(
                            paths       = paths,
                            onOpenAbout = onOpenAbout,
                        )
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
