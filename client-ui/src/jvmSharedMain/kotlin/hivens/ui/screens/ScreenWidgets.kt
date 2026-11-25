package hivens.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import hivens.accounts.api.AccountKeys
import hivens.accounts.api.SessionState
import hivens.accounts.api.SessionView
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.CataloguePages
import hivens.i18n.api.I18nKeys
import hivens.i18n.api.LocalTranslator
import hivens.packs.api.PackPages
import hivens.ui.background.BackgroundSettings
import hivens.ui.feature.catalogue.browse.BrowseScreen
import hivens.ui.feature.catalogue.browse.CataloguePackDetailScreen
import hivens.ui.feature.catalogue.project.ModDetailScreen
import hivens.ui.feature.catalogue.project.ModVersionScreen
import hivens.ui.navigation.LauncherConfigs
import hivens.ui.navigation.PackSettingsPage
import hivens.ui.screens.detail.PackDetailScreen
import hivens.ui.screens.detail.settings.PackSettingsCategory
import hivens.ui.screens.detail.versions.PackVersionsScreen
import hivens.ui.screens.library.LibraryScreen
import hivens.ui.screens.settings.SettingsScreen
import hivens.ui.utils.ConsoleSettingsStore
import hivens.ui.widgets.Commands
import hivens.ui.widgets.Sources
import hivens.ui.widgets.about.AboutSurface
import hivens.ui.widgets.bgsettings.BgSettingsSurface
import hivens.ui.widgets.profile.ProfileSurface
import hivens.ui.widgets.themepicker.ThemePickerSurface
import hivens.ui.widgets.wardrobe.WardrobeSurface
import hivens.widget.api.CompositionKeys
import hivens.widget.api.SettingsChange
import hivens.widget.api.rememberScopedAction
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.ConfigId
import hivens.widget.model.PageValue
import hivens.widget.model.Show
import hivens.widget.model.Widget
import hivens.widget.model.WidgetKind
import org.koin.compose.koinInject

// Each of the launcher's own screens as the one widget its config holds, until the screens are made of widgets
// themselves. None is an argument's: what an opening sets is a value of the page, and the rest is a source or a
// command like any widget's. Not removable, which keeps them out of the palette, and with no arrival of their own,
// since the config that holds one arrives.

/** The kind of the widget that draws [config] whole. */
internal fun screenKind(config: ConfigId): WidgetKind = WidgetKind("screen.${config.value}")

@Composable
private fun rememberShow(): (Show) -> Unit = rememberScopedCommand(CompositionKeys.Show) ?: {}

@Composable
private fun rememberBack(): () -> Unit = rememberScopedAction(CompositionKeys.Back) ?: {}

@Composable
private fun <T : Any> pageValue(value: PageValue<T>): T? = rememberScopedSource(value.key).valueOrNull()

@Composable
private fun sessionState(): SessionState? = rememberScopedSource(AccountKeys.Session).valueOrNull()

@Composable
private fun session(): SessionView? = (sessionState() as? SessionState.SignedIn)?.session

@Widget(id = "screen.home", enter = "none", removable = false)
@Composable
fun HomeScreenWidget() = NewHomeScreen()

@Widget(id = "screen.library", enter = "none", removable = false)
@Composable
fun LibraryScreenWidget() {
    val show = rememberShow()
    LibraryScreen(onOpenPack = { show(LauncherConfigs.pack(it)) })
}

@Widget(id = "screen.browse", enter = "none", removable = false)
@Composable
fun BrowseScreenWidget() = BrowseScreen(intoInstanceId = pageValue(CataloguePages.Into))

@Widget(id = "screen.profile", enter = "none", removable = false)
@Composable
fun ProfileScreenWidget() = ProfileSurface(session = session(), authResolving = sessionState() is SessionState.Resolving)

@Widget(id = "screen.wardrobe", enter = "none", removable = false)
@Composable
fun WardrobeScreenWidget() = WardrobeSurface(session = session(), onBack = rememberBack())

// The language module's keys where it is brought in, and the one language in use where it is not.
@Widget(id = "screen.settings", enter = "none", removable = false)
@Composable
fun SettingsScreenWidget() {
    val show = rememberShow()
    val language = rememberScopedSource(I18nKeys.Language).valueOrNull() ?: LocalTranslator.current.language
    val languages = rememberScopedSource(I18nKeys.Languages).valueOrNull() ?: listOf(language)
    val update = rememberScopedCommand(I18nKeys.UpdateSettings)
    SettingsScreen(
        currentLanguage          = language,
        languages                = languages,
        onLanguageChanged        = { tag -> update?.invoke(SettingsChange { it.copy(language = tag) }) },
        onOpenBackgroundSettings = { show(Show(LauncherConfigs.Background)) },
        onOpenAbout              = { show(Show(LauncherConfigs.About)) },
    )
}

@Widget(id = "screen.theme.picker", enter = "none", removable = false)
@Composable
fun ThemesScreenWidget() = ThemePickerSurface()

@Widget(id = "screen.about", enter = "none", removable = false)
@Composable
fun AboutScreenWidget() = AboutSurface(onBack = rememberBack())

@Widget(id = "screen.background", enter = "none", removable = false)
@Composable
fun BackgroundScreenWidget() {
    val update = rememberScopedCommand(Commands.UpdateBackground)
    BgSettingsSurface(
        currentSettings   = rememberScopedSource(Sources.Background).valueOrNull() ?: BackgroundSettings(),
        onSettingsChanged = { change -> update?.invoke(change) },
        onBack            = rememberBack(),
    )
}

@Widget(id = "screen.pack", enter = "none", removable = false)
@Composable
fun PackScreenWidget() {
    val pack = pageValue(PackPages.Pack) ?: return
    val settings = pageValue(PackSettingsPage)
    val show = rememberShow()
    val rewrite = rememberScopedCommand(CompositionKeys.Rewrite)
    // Aims the window's browse at the pack, where a catalogue module answers it.
    val aim = rememberScopedCommand(CatalogueKeys.Aim)
    PackDetailScreen(
        instanceId             = pack,
        appState               = sessionState(),
        onBack                 = rememberBack(),
        initialShowSettings    = settings != null,
        initialSettingsSection = PackSettingsCategory.entries.firstOrNull { it.name == settings },
        onOpenVersions         = { fromSettings ->
            // The builds are reached from the section named, so that is the one back lands on.
            if (fromSettings) rewrite?.invoke(mapOf(PackSettingsPage.of(PackSettingsCategory.Version.name)))
            show(LauncherConfigs.packVersions(pack))
        },
        onOpenProject          = { show(LauncherConfigs.project(it)) },
        onFindProjects         = {
            aim?.invoke(pack)
            show(LauncherConfigs.browseInto(pack))
        },
    )
}

@Widget(id = "screen.pack.versions", enter = "none", removable = false)
@Composable
fun PackVersionsScreenWidget() {
    val pack = pageValue(PackPages.Pack) ?: return
    PackVersionsScreen(instanceId = pack, onBack = rememberBack())
}

@Widget(id = "screen.catalogue.pack", enter = "none", removable = false)
@Composable
fun CataloguePackScreenWidget() {
    val pack = pageValue(CataloguePages.Pack) ?: return
    val show = rememberShow()
    CataloguePackDetailScreen(
        origin      = pack.origin,
        packId      = pack.packId,
        onBack      = rememberBack(),
        onInstalled = { show(LauncherConfigs.pack(it)) },
    )
}

@Widget(id = "screen.project", enter = "none", removable = false)
@Composable
fun ProjectScreenWidget() {
    val target = pageValue(CataloguePages.Project) ?: return
    val show = rememberShow()
    ModDetailScreen(target = target, onOpenVersion = { id, number -> show(LauncherConfigs.build(target, id, number)) })
}

@Widget(id = "screen.project.build", enter = "none", removable = false)
@Composable
fun BuildScreenWidget() {
    val target = pageValue(CataloguePages.Project) ?: return
    val build = pageValue(CataloguePages.Build) ?: return
    ModVersionScreen(target = target, versionId = build.versionId)
}

// The console's own config. What it is drawn on is its window's, or its layer's on a phone.
@Widget(id = "screen.console", enter = "none", removable = false)
@Composable
fun ConsoleScreenWidget() {
    val store: ConsoleSettingsStore = koinInject()
    val settings by store.settings.collectAsState()
    ConsoleContent(settings = settings, onSettingsChange = store::update)
}
