package hivens.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
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
 * save() mirrors [Protocol.setMimicLauncherVersion] inline -- that read is
 * per-protocol-call, so without the mirror the user would need a restart
 * for the knob to take effect.
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

                // One scroll state per category, so a category opens at its top.
                // Shared, it survived the switch: reading the bottom of Console
                // and clicking Advanced landed part-way down a page that had
                // never been scrolled.
                val categoryScroll = remember(selectedCategory) { ScrollState(0) }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(categoryScroll)
                        // Inside the scrolled content: without it the last plane
                        // ended flush against the clip with its bevel cut through.
                        .padding(bottom = 24.dp),
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
                        SettingsCategory.Console -> ConsoleSection()
                        SettingsCategory.Network -> NetworkSection()
                        SettingsCategory.Smarty -> SmartySection(
                            form = form,
                            save = ::save,
                        )
                        SettingsCategory.Advanced -> AdvancedSection(
                            paths           = paths,
                            form            = form,
                            save            = ::save,
                            initialSettings = initialSettings,
                        )
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
