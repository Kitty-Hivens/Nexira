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
import hivens.ui.navigation.LauncherScreens
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
import hivens.widget.api.SettingsChange
import hivens.widget.api.rememberScopedAction
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.ConfigId
import hivens.pages.api.PageValue
import hivens.pages.api.Show
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import org.koin.compose.koinInject
import hivens.history.api.HistoryKeys
import hivens.pages.api.PagesKeys

// Each of the launcher's own screens as the widget its config holds, a container whose slots hold the widgets the
// screen is made of, until the screens are made of widgets themselves. None is an argument's: what an opening sets is
// a value of the page, and the rest is a source or a command like any widget's. Not removable, which keeps them out of
// the palette, and with no arrival of their own, since the config that holds one arrives.

/** The kind of the widget that draws [config] whole. */
internal fun screenKind(config: ConfigId): WidgetKind = WidgetKind("screen.${config.value}")

@Composable
private fun rememberShow(): (Show) -> Unit = rememberScopedCommand(PagesKeys.Show) ?: {}

@Composable
private fun rememberBack(): () -> Unit = rememberScopedAction(HistoryKeys.Back) ?: {}

@Composable
private fun <T : Any> pageValue(value: PageValue<T>): T? = rememberScopedSource(value.key).valueOrNull()

@Composable
private fun sessionState(): SessionState? = rememberScopedSource(AccountKeys.Session).valueOrNull()

@Composable
private fun session(): SessionView? = (sessionState() as? SessionState.SignedIn)?.session

@Widget(id = "screen.home", enter = "none", removable = false, slots = ["main"])
@Composable
fun HomeScreenWidget(instance: WidgetInstance) = NewHomeScreen(instance)

@Widget(id = "screen.library", enter = "none", removable = false, slots = ["header", "body"])
@Composable
fun LibraryScreenWidget(instance: WidgetInstance) {
    val show = rememberShow()
    LibraryScreen(instance, onOpenPack = { show(LauncherScreens.pack(it)) })
}

@Widget(id = "screen.browse", enter = "none", removable = false, slots = ["header", "body"])
@Composable
fun BrowseScreenWidget(instance: WidgetInstance) = BrowseScreen(instance, intoInstanceId = pageValue(CataloguePages.Into))

@Widget(id = "screen.profile", enter = "none", removable = false, slots = ["nav", "skin", "account", "signin"])
@Composable
fun ProfileScreenWidget(instance: WidgetInstance) =
    ProfileSurface(instance, session = session(), authResolving = sessionState() is SessionState.Resolving)

@Widget(id = "screen.wardrobe", enter = "none", removable = false)
@Composable
fun WardrobeScreenWidget() = WardrobeSurface(session = session(), onBack = rememberBack())

// The language module's keys where it is brought in, and the one language in use where it is not.
@Widget(id = "screen.settings", enter = "none", removable = false, slots = ["theme.link", "theme.daynight"])
@Composable
fun SettingsScreenWidget(instance: WidgetInstance) {
    val show = rememberShow()
    val language = rememberScopedSource(I18nKeys.Language).valueOrNull() ?: LocalTranslator.current.language
    val languages = rememberScopedSource(I18nKeys.Languages).valueOrNull() ?: listOf(language)
    val update = rememberScopedCommand(I18nKeys.UpdateSettings)
    SettingsScreen(
        instance                 = instance,
        currentLanguage          = language,
        languages                = languages,
        onLanguageChanged        = { tag -> update?.invoke(SettingsChange { it.copy(language = tag) }) },
        onOpenBackgroundSettings = { show(Show(LauncherScreens.Background)) },
        onOpenAbout              = { show(Show(LauncherScreens.About)) },
    )
}

@Widget(id = "screen.theme.picker", enter = "none", removable = false, slots = ["header", "grid", "preview"])
@Composable
fun ThemesScreenWidget(instance: WidgetInstance) = ThemePickerSurface(instance)

@Widget(id = "screen.about", enter = "none", removable = false, slots = ["left", "right"], hands = ["about.update", "about.update.open"])
@Composable
fun AboutScreenWidget(instance: WidgetInstance) = AboutSurface(instance, onBack = rememberBack())

@Widget(id = "screen.background", enter = "none", removable = false, slots = ["controls", "theme", "theme.link", "motion"])
@Composable
fun BackgroundScreenWidget(instance: WidgetInstance) {
    val update = rememberScopedCommand(Commands.UpdateBackground)
    BgSettingsSurface(
        instance          = instance,
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
    val rewrite = rememberScopedCommand(HistoryKeys.Rewrite)
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
            show(LauncherScreens.packVersions(pack))
        },
        onOpenProject          = { show(LauncherScreens.project(it)) },
        onFindProjects         = {
            aim?.invoke(pack)
            show(LauncherScreens.browseInto(pack))
        },
    )
}

@Widget(id = "screen.pack.versions", enter = "none", removable = false)
@Composable
fun PackVersionsScreenWidget() {
    val pack = pageValue(PackPages.Pack) ?: return
    PackVersionsScreen(instanceId = pack, onBack = rememberBack())
}

@Widget(id = "screen.catalogue.pack", enter = "none", removable = false, slots = ["header", "body"], hands = ["catalogue.pack.page"])
@Composable
fun CataloguePackScreenWidget(instance: WidgetInstance) {
    val pack = pageValue(CataloguePages.Pack) ?: return
    val show = rememberShow()
    CataloguePackDetailScreen(
        instance    = instance,
        origin      = pack.origin,
        packId      = pack.packId,
        onBack      = rememberBack(),
        onInstalled = { show(LauncherScreens.pack(it)) },
    )
}

@Widget(id = "screen.project", enter = "none", removable = false, slots = ["header", "body"], hands = ["project.page"])
@Composable
fun ProjectScreenWidget(instance: WidgetInstance) {
    val target = pageValue(CataloguePages.Project) ?: return
    val show = rememberShow()
    ModDetailScreen(instance, target = target, onOpenVersion = { id, number -> show(LauncherScreens.build(target, id, number)) })
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
