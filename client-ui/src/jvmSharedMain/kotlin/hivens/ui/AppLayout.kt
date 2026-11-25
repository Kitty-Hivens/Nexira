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
import hivens.ui.host.CompositionWindows
import hivens.ui.navigation.CompositionHistory
import hivens.ui.navigation.LauncherConfigs
import hivens.ui.screens.screenKind
import hivens.widget.api.LocalWidgetRegistry
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import hivens.ui.nx.NxSwap
import hivens.ui.puppet.PuppetClick
import hivens.ui.screens.*
import hivens.ui.feature.catalogue.browse.LocalCatalogueSearch
import hivens.ui.feature.catalogue.browse.rememberShellCatalogueSearch
import hivens.ui.theme.Plane
import hivens.ui.theme.Backdrop
import hivens.ui.theme.LocalPlane
import hivens.ui.utils.releaseFocusOnPress
import hivens.ui.widgets.shell.LocalCenterBody
import hivens.ui.feature.catalogue.project.LocalLinkFollower
import hivens.ui.feature.catalogue.project.rememberNavigatingLinkFollower
import hivens.ui.screens.custom.CustomScreen
import hivens.ui.widgets.Commands
import hivens.ui.widgets.LevelHost
import hivens.ui.widgets.MisplacedWidget
import hivens.ui.widgets.Sources
import hivens.widget.api.CompositionKeys
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalMisplacedWidget
import hivens.widget.api.LocalScope
import hivens.widget.api.PageLevels
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
import hivens.widget.model.History
import hivens.widget.model.HistoryEntry
import hivens.widget.model.ScopeLevel
import hivens.widget.model.WidgetInstance
import hivens.widget.model.screen
import hivens.widget.model.Show
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import org.koin.compose.koinInject

// ─── Layout ──────────────────────────────────────────────────────────────────

@Composable
fun AppLayout(
    history: CompositionHistory,
    show: (Show) -> Unit,
    backgroundSettings: BackgroundSettings = BackgroundSettings(),
    customization: CustomizationSettings = CustomizationSettings(),
    onCustomizationChanged: (CustomizationSettings) -> Unit = {},
) {
    val windowClass = rememberWindowClass()
    val now = history.now

    // Go transparent only when the wallpaper can actually be drawn: a deleted image
    // left the row transparent over a blank white window. Everything inside is told
    // which of the two it sits on, so a plane over a picture knows it is over one.
    val overWallpaper = backgroundSettings.hasUsableImage()
    val rowBackground = if (overWallpaper) Color.Transparent else NxColor.page
    val page = Plane(step = 0, color = NxColor.page, over = if (overWallpaper) Backdrop.Wallpaper else Backdrop.Page)

    // The centre region's router. Here and not in the layout graph because what a config
    // holds is not in a document yet; the centre region's widget draws it.
    val centerBody: @Composable () -> Unit = {
        ScreenRouter(history.now) { entry -> ConfigBody(entry.config) }
    }

    // What this composition's widgets fire to move its history and read of where it
    // stands, put over the composition's level, which AppRoot made around everything
    // it draws. Around the editor too, so a widget the palette draws reads the same
    // answers as one on a surface, and a widget anywhere in the shell finds them,
    // where a context provided by one screen pinned its widgets to that screen.
    // Everything else a widget reads here, the session and the appearance among it,
    // the level makes for itself.
    ProvideComposition(history, show) {
    // The editor host wraps the WHOLE shell (rails included) so its decorators
    // reach rail widgets; it puts its own chrome back over the content pane from
    // what the centre region reports. The shell itself is a widget surface:
    // appshell.root lays its three region widgets in a Row.
    EditorSurfaceHost(
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
            LocalCenterBody provides centerBody,
            LocalLinkFollower provides rememberNavigatingLinkFollower(),
            LocalCatalogueSearch provides rememberShellCatalogueSearch(now.current, show),
            LocalPlane provides page,
            LocalWindowClass provides windowClass,
        ) {
            SlotRenderer(
                surface  = SurfaceId("appshell.root"),
                slot     = SlotId("regions"),
                modifier = Modifier.fillMaxSize().background(rowBackground).releaseFocusOnPress(),
            )
        }
    }
    } // end ProvideComposition
}

/**
 * What [config] holds: the one widget that is a screen of the launcher's own, or a screen
 * somebody made, whose id the config is.
 */
@Composable
internal fun ConfigBody(config: ConfigId) {
    val kind = screenKind(config)
    val screen = LocalWidgetRegistry.current[kind]
    if (screen == null) {
        CustomScreen(config.value)
    } else {
        screen.Render(remember(kind) { WidgetInstance(kind = kind, instanceId = kind.value) })
    }
}

/**
 * Puts the composition's [history] over its level for [content]: where it stands and
 * the commands that move it, the keys only the owner of a history can answer. [show] is
 * the history's own, refusing a config the composition does not have. With them the
 * windows the composition owns: which are open, and the commands that open and close
 * them, drawn here so that they go when the composition goes.
 * Each command is one object for the composition's life that runs the latest
 * lambda it was handed, so a reader does not recompose because the shell did.
 *
 * Widgets that cannot stand without one of them draw the placeholder for a widget
 * that stands where something it reads has no answer, which is also put here.
 */
@Composable
internal fun ProvideComposition(history: CompositionHistory, show: (Show) -> Unit, content: @Composable () -> Unit) {
    val document: Document = koinInject()
    // The composition the launcher starts in has its window already.
    val owned = remember(document) { CompositionWindows { it in document.compositions && it != document.start } }
    val openOwned by owned.open.collectAsState()
    ProvideScoped(CompositionKeys.History, history.now) {
    ProvideScopedCommand(CompositionKeys.Show, rememberLatestCommand(show)) {
    ProvideScopedCommand(CompositionKeys.Back, rememberLatestCommand<Unit> { history.back() }) {
    ProvideScopedCommand(CompositionKeys.Forward, rememberLatestCommand<Unit> { history.forward() }) {
    ProvideScopedCommand(CompositionKeys.BackTo, rememberLatestCommand(history::backTo)) {
    ProvideScopedCommand(CompositionKeys.Rewrite, rememberLatestCommand(history::rewrite)) {
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
    }
    }
    }
    }
}

/**
 * Draws the entry [history] shows with [content], each page in its own saveable state
 * and page level, and swaps one for the next with the swap primitive's transition.
 *
 * Pages are disposed when they are swapped out, so everything a reader had
 * arranged, a scroll position, an open tab, a chosen category, died with the visit
 * and came back at its default. It is held per page here instead, and so is
 * a page level, for what a page makes and is handed, its entry's values among it. A
 * page level lives while its page is anywhere in the history, back or forward, and
 * goes once it has left the history and stopped drawing. The composition's level keeps
 * them, so they go with it even if this router leaves first.
 */
@Composable
internal fun ScreenRouter(history: History, content: @Composable (HistoryEntry) -> Unit) {
    val retention = rememberSaveableStateHolder()
    val pages = LocalScope.current?.nearest(ScopeLevel.Composition)?.pages
    val reachable = history.reachable.mapTo(HashSet()) { it.page }
    LaunchedEffect(pages, reachable) { pages?.keep(reachable) }
    // How a page replaces another is the swap primitive's business, not the
    // router's. A still style collapses it without this site knowing. Two entries
    // of one page are one content, which the entry changes in place: drawn
    // side by side during the swap they held one saved state twice, which throws.
    NxSwap(
        target     = history.current,
        label      = "screen",
        contentKey = { it.page },
    ) { target ->
        Page(retention, pages, target) { content(target) }
    }
}

/**
 * One page of the router: its saveable state and its page level, both held under the
 * [entry]'s page. The content holds its level from the moment it composes, so a page
 * that left the history keeps its level until it has finished fading out, and one that
 * just arrived cannot be swept by another leaving in its first frame.
 */
@Composable
private fun Page(
    retention: SaveableStateHolder,
    pages: PageLevels?,
    entry: HistoryEntry,
    content: @Composable () -> Unit,
) {
    val key = entry.page
    retention.SaveableStateProvider(key) {
        val level = pages?.let {
            val _ = remember(it, key) { it.hold(key) }
            it.levelFor(entry)
        }
        LevelHost(level, content)
    }
}

// ─── Sidebar ─────────────────────────────────────────────────────────────────

@Composable
fun AppSidebar(
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
    LocalLayoutGraph.current.screens.forEach { spec ->
        key(spec.id) { PuppetClick("nav.screen.${spec.id}") { onSection(ConfigId(spec.id)) } }
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
                SlotRenderer(SurfaceId(SIDEBAR_SURFACE), SlotId("top"), Modifier.fillMaxWidth(), spacing = 0.dp)
                Column(Modifier.fillMaxWidth()) {
                    SlotRenderer(SurfaceId(SIDEBAR_SURFACE), SlotId("bottom"), Modifier.fillMaxWidth(), spacing = 0.dp)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

private const val SIDEBAR_SURFACE = "appshell.leftrail"
