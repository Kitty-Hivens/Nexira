package hivens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import hivens.ui.background.BackgroundSettings
import hivens.ui.chrome.LocalWindowClass
import hivens.ui.chrome.rememberWindowClass
import hivens.ui.background.hasUsableImage
import hivens.ui.customization.CustomizationSettings
import hivens.ui.editor.EditorSurfaceHost
import hivens.ui.layout.DocumentStore
import hivens.ui.host.CompositionWindows
import hivens.ui.navigation.LauncherConfigs
import hivens.ui.puppet.PuppetClick
import hivens.ui.screens.*
import hivens.ui.feature.catalogue.browse.LocalCatalogueSearch
import hivens.ui.feature.catalogue.browse.rememberShellCatalogueSearch
import hivens.ui.theme.Plane
import hivens.ui.theme.Backdrop
import hivens.ui.theme.LocalPlane
import hivens.ui.utils.releaseFocusOnPress
import hivens.ui.feature.catalogue.project.LocalLinkFollower
import hivens.ui.feature.catalogue.project.rememberNavigatingLinkFollower
import hivens.ui.widgets.Commands
import hivens.ui.widgets.MisplacedWidget
import hivens.ui.widgets.Sources
import hivens.widget.api.CompositionKeys
import hivens.widget.api.LocalDocument
import hivens.widget.api.LocalScope
import hivens.widget.api.WidgetScope
import hivens.widget.api.flowSource
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.flow.MutableStateFlow
import hivens.widget.api.LocalMisplacedWidget
import hivens.widget.api.ProvideScoped
import hivens.widget.api.ProvideScopedCommand
import hivens.widget.api.SlotRenderer
import hivens.widget.api.rememberLatestCommand
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.CompositionName
import hivens.widget.model.ConfigId
import hivens.widget.model.Document
import hivens.history.api.History
import hivens.pages.api.Show
import hivens.widget.model.SlotId
import hivens.widget.model.WidgetInstance
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import org.koin.compose.koinInject
import hivens.history.api.HistoryKeys
import hivens.pages.api.PagesKeys

// ─── Layout ──────────────────────────────────────────────────────────────────

@Composable
fun AppLayout(
    /** Where the composition's history stands, see [rememberCompositionHistory]. */
    history: History,
    show: (Show) -> Unit,
    backgroundSettings: BackgroundSettings = BackgroundSettings(),
    customization: CustomizationSettings = CustomizationSettings(),
    onCustomizationChanged: (CustomizationSettings) -> Unit = {},
) {
    val windowClass = rememberWindowClass()
    val now = history

    // Go transparent only when the wallpaper can actually be drawn: a deleted image
    // left the row transparent over a blank white window. Everything inside is told
    // which of the two it sits on, so a plane over a picture knows it is over one.
    val overWallpaper = backgroundSettings.hasUsableImage()
    val rowBackground = if (overWallpaper) Color.Transparent else NxColor.page
    val page = Plane(step = 0, color = NxColor.page, over = if (overWallpaper) Backdrop.Wallpaper else Backdrop.Page)

    // The interface as a document: the person's own where they have one, the standard one where not.
    val store: DocumentStore = koinInject()
    val document by store.document.collectAsState()

    // The config drawn and the windows the composition owns, put over the composition's
    // level, which AppRoot made around everything it draws. Around the editor too, so a
    // widget the palette draws reads the same answers as one on a surface. Everything
    // else a widget reads here, the history and the session among it, the level makes
    // for itself.
    ProvideComposition(now.current.config) {
    // The editor host wraps the WHOLE shell (rails included) so its decorators
    // reach rail widgets; it puts its own chrome back over the content pane from
    // what the centre region reports. The shell itself is the config's layout.
    EditorSurfaceHost(
        composition            = LauncherConfigs.Composition,
        config                 = now.current.config,
        customization          = customization,
        onCustomizationChanged = onCustomizationChanged,
        onOpen                 = { show(LauncherConfigs.section(it)) },
    ) {
        // Links that point at a project the launcher can draw stop going out to a
        // browser from here down, and a project's tags open the catalogue. Provided
        // at the shell rather than per surface, so a markdown body, a page header and
        // a rail widget follow the same rule.
        CompositionLocalProvider(
            LocalLinkFollower provides rememberNavigatingLinkFollower(),
            LocalCatalogueSearch provides rememberShellCatalogueSearch(now.current, show),
            LocalPlane provides page,
            LocalWindowClass provides windowClass,
        ) {
            ConfigView(
                name     = LauncherConfigs.Composition,
                document = document,
                history  = now,
                modifier = Modifier.fillMaxSize().background(rowBackground).releaseFocusOnPress(),
            )
        }
    }
    } // end ProvideComposition
}

/**
 * Puts over the composition's level for [content] the [config] it draws, and the windows
 * the composition owns: which are open, and the commands that open and close them, drawn
 * here so that they go when the composition goes. Each command is one object for the
 * composition's life that runs the latest lambda it was handed, so a reader does not
 * recompose because the shell did.
 *
 * Widgets that cannot stand without what they read draw the placeholder for a widget
 * that stands where something it reads has no answer, which is also put here.
 */
@Composable
internal fun ProvideComposition(config: ConfigId, content: @Composable () -> Unit) {
    val store: DocumentStore = koinInject()
    // Read as it is when a window is asked for, so a document changed by an edit does not
    // make the windows anew and close the ones open.
    val document by rememberUpdatedState(store.document.collectAsState().value)
    // The composition the launcher starts in has its window already.
    val owned = remember { CompositionWindows { it in document.compositions && it != document.start } }
    val openOwned by owned.open.collectAsState()
    ProvideScoped(CompositionKeys.Config, config) {
    ProvideScoped(CompositionKeys.Windows, openOwned) {
    ProvideScopedCommand(CompositionKeys.ShowWindow, rememberLatestCommand<CompositionName> { owned.show(it) }) {
    ProvideScopedCommand(CompositionKeys.CloseWindow, rememberLatestCommand(owned::close)) {
        CompositionLocalProvider(LocalMisplacedWidget provides MisplacedWidget) {
            content()
            OwnedWindows(document, owned)
        }
    }
    }
    }
    }
}

/**
 * Where the history of the composition this level is stands, as the history module keeps it, and what shows a page
 * in it. Where the document brings in no history module, the composition stands on [start] and shows nothing else.
 * Read inside the composition's level, after [ProvideCompositionFacts].
 */
@Composable
internal fun rememberCompositionHistory(start: ConfigId): Pair<History, (Show) -> Unit> {
    val history = rememberScopedSource(HistoryKeys.History).valueOrNull() ?: remember(start) { History(start) }
    val command = rememberScopedCommand(PagesKeys.Show)
    val latest by rememberUpdatedState(command)
    val show: (Show) -> Unit = remember { { latest?.invoke(it) } }
    return history to show
}

/**
 * Hands the composition's level what the document says of the composition [name]: its name, the config it starts on
 * and the configs it has, as the document changes. Into the level itself rather than over it, so what a module makes
 * there reads them as it makes it: the history module keeps a composition's history by its name, starts it where the
 * document does and lets go of a config the document no longer has.
 */
@Composable
internal fun ProvideCompositionFacts(name: CompositionName, document: Document) {
    val level = LocalScope.current?.nearest(ScopeLevel.Composition) ?: return
    val composition = document.compositions[name] ?: return
    val configs = composition.configs.keys
    val facts = remember(level, name, composition.start) { CompositionFacts(level, name, composition.start, configs) }
    SideEffect { facts.configs.value = configs }
}

/** What [ProvideCompositionFacts] put into [level], taken out again when it leaves the composition. */
private class CompositionFacts(level: WidgetScope, name: CompositionName, start: ConfigId, configs: Set<ConfigId>) : RememberObserver {
    val configs = MutableStateFlow(configs)

    private val handles = listOf(
        level.provide(CompositionKeys.Name, name),
        level.provide(CompositionKeys.Start, start),
        level.provideSource(CompositionKeys.Configs, flowSource(this.configs)),
    )

    override fun onRemembered() = Unit
    override fun onForgotten() = close()
    override fun onAbandoned() = close()

    private fun close() = handles.forEach { it.close() }
}

// ─── Sidebar ─────────────────────────────────────────────────────────────────

@Composable
fun AppSidebar(
    /** The left region, whose slots the rail's entries stand in. */
    instance: WidgetInstance,
    isAuthenticated: Boolean,
    onSection: (ConfigId) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier.width(64.dp).fillMaxHeight(),
) {
    val consoleVisible = rememberScopedSource(Sources.ConsoleVisible).valueOrNull() == true
    val setConsoleVisible = rememberScopedCommand(Commands.SetConsoleVisible)

    // Puppet: sidebar navigation.
    PuppetClick("nav.home")     { onSection(LauncherConfigs.Home) }
    PuppetClick("nav.library")  { onSection(LauncherConfigs.Library) }
    PuppetClick("nav.browse")   { onSection(LauncherConfigs.Browse) }
    PuppetClick("nav.profile") { onSection(LauncherConfigs.Profile) }
    PuppetClick("nav.wardrobe") { onSection(LauncherConfigs.Wardrobe) }
    PuppetClick("nav.settings") { onSection(LauncherConfigs.Settings) }
    PuppetClick("nav.about")    { onSection(LauncherConfigs.About) }
    PuppetClick("nav.console")  { setConsoleVisible?.invoke(!consoleVisible) }
    if (isAuthenticated) {
        PuppetClick("nav.logout") { onLogout() }
    }
    // A made screen has no fixed entry above, so it is driven by its id.
    val standard: Document = koinInject()
    val configs = LocalDocument.current?.compositions?.get(LauncherConfigs.Composition)?.configs?.keys.orEmpty()
    val shipped = standard.compositions[LauncherConfigs.Composition]?.configs?.keys.orEmpty()
    (configs - shipped).forEach { id ->
        key(id) { PuppetClick("nav.screen.${id.value}") { onSection(id) } }
    }

    NavigationRail(
        modifier       = modifier,
        // Transparent: the rail's NxSurface wrapper (ShellLeftRegion) owns the
        // background now, so its own opacity and blur drive the matte.
        containerColor = Color.Transparent,
        contentColor   = NxInk.quiet,
        // The rail's own system insets would stack on the ones the region already applies.
        windowInsets   = WindowInsets(0.dp),
    ) {
        // Items sit flush (spacing 0) so the rail is one contiguous column
        // of clickable slots with no dead gap between buttons. Each NavSlot
        // is taller than its icon and centers it, so the breathing room is
        // the slot's own padding -- and stays clickable. No leading spacer
        // for the same reason; fillMaxWidth keeps the slot's Column at rail
        // width so items center.
        // Scrolls when the window is shorter than the rail's content, which a phone held
        // sideways is: the bottom group was cut off with no way to reach it. When it fits,
        // the minimum height keeps the top group up and the bottom group down as before.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                SlotRenderer(instance, SlotId("top"), Modifier.fillMaxWidth(), spacing = 0.dp)
                Column(Modifier.fillMaxWidth()) {
                    SlotRenderer(instance, SlotId("bottom"), Modifier.fillMaxWidth(), spacing = 0.dp)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
