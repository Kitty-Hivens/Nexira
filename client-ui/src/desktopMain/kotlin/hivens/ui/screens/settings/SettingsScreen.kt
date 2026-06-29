package hivens.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.config.ExperimentalProtocolOverride
import hivens.config.Protocol
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.HomeView
import hivens.core.data.UiStyle
import hivens.launcher.network.NetworkState
import hivens.launcher.platform.PlatformPaths
import hivens.ui.surface.NxCard
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetScreen
import org.koin.compose.koinInject

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
    onOpenAbout: () -> Unit = {}
) {
    PuppetScreen("Settings")

    val settingsService: ISettingsService = koinInject()
    val paths: PlatformPaths              = koinInject()
    val s = LocalStrings.current

    val initialSettings = remember { settingsService.getSettings() }
    val form            = remember { SettingsFormState(initialSettings) }
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
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // Title lives in the top-bar breadcrumb now -- no in-screen duplicate.
        // The frame is an NxCard: a library-owned tonal body + bevel hairline that
        // stays a distinct plane under any style and with no wallpaper, instead of
        // a glass-alpha that collapsed when the coat came off.
        NxCard(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            level    = NxSurfaceLevel.Raised,
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
                            currentLocale                = currentLocale,
                            onLocaleChanged              = onLocaleChanged,
                            homeView                     = homeView,
                            onHomeViewChanged            = onHomeViewChanged,
                            uiStyle                      = uiStyle,
                            onUiStyleChanged             = onUiStyleChanged,
                        )
                        SettingsCategory.Console -> ConsoleSection(paths = paths)
                        SettingsCategory.Network -> NetworkSection(
                            form = form,
                            save = ::save,
                        )
                        SettingsCategory.Smarty -> SmartySection(
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

    }
}
