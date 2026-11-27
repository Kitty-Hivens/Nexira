package hivens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil3.ComponentRegistry
import coil3.SingletonImageLoader
import hivens.themes.api.ThemeInputs
import hivens.ui.background.BackgroundManager
import hivens.ui.background.BackgroundSettings
import hivens.ui.components.SignOutPrompt
import hivens.ui.customization.CustomizationSettings
import hivens.ui.host.CompositionWindows
import hivens.ui.layout.DocumentStore
import hivens.ui.navigation.LauncherConfigs
import hivens.ui.navigation.LauncherScreens
import hivens.ui.navigation.NavRequests
import hivens.config.Branding
import hivens.ui.i18n.nexiraStrings
import hivens.ui.notifications.Kind
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.Severity
import hivens.ui.notifications.render.NotificationStack
import hivens.ui.pictures.PictureRechecks
import hivens.ui.pictures.WarmLibrary
import hivens.ui.pictures.pictureLoader
import hivens.ui.theme.NxColor
import hivens.themes.api.WallpaperTone
import hivens.ui.widgets.ChildLevel
import hivens.ui.host.launcherServices
import hivens.widget.api.LocalScope
import hivens.widget.api.rememberScopedCommand
import hivens.history.api.HistoryKeys
import hivens.widget.model.Document
import hivens.history.api.History
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import hivens.ui.widgets.modules.LocalWidgetLabels
import hivens.ui.widgets.modules.ScreenEffectHost
import hivens.ui.widgets.modules.WidgetLabels
import hivens.ui.widgets.modules.WidgetModules
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/**
 * Whatever the host feeds into the history besides the screens: thumb buttons on a
 * desktop, the system back gesture on a phone, as [back] and [forward]. A back with
 * nowhere to go stays in the history as [History.backsAtRoot] and never reaches the
 * host as a way out, and where no history answers, a back goes nowhere.
 */
@Composable
internal expect fun NavigationInputs(back: () -> Unit, forward: () -> Unit)

/** The wallpaper behind the layout, which reports its tone upward. A host with no wallpaper draws nothing and reports none. */
@Composable
internal expect fun WallpaperLayer(
    settings: BackgroundSettings,
    mousePosProvider: () -> Offset,
    onTone: (WallpaperTone) -> Unit,
    onAudioVolume: (Float) -> Unit,
)

/**
 * The windows a composition owns, which [windows] has open: each a window of its own on a host that shows several,
 * or over the one window a phone has. Drawn inside the composition, so they go when it goes.
 */
@Composable
internal expect fun OwnedWindows(document: Document, windows: CompositionWindows)

/** The image decoders the host adds on top of the common ones. */
internal expect fun ComponentRegistry.Builder.addPlatformDecoders()

/**
 * The launcher's interface inside one window: its composition level, the history,
 * the wallpaper and the layout.
 *
 * The composition's level is made here, around everything it draws. How it looks is
 * read through the sources like any widget reads it, and what the wallpaper shows is
 * told to the themes module through the inputs it keeps for the host, which decides the
 * dark scheme and the wallpaper theme from it.
 */
@Composable
fun AppRoot(
    customization: CustomizationSettings,
    onCustomizationChanged: (CustomizationSettings) -> Unit,
) {
    val dataDirectory: Path                    = koinInject()
    val json: Json                             = koinInject()
    // Smartycraft-routed Call.Factory for Coil's image fetcher. The
    // bypass / direct routing rule lives in Modules.kt alongside the
    // same rule for the Ktor HttpClientProvider; both must agree or
    // news / skin images would diverge from auth and protocol traffic
    // on the same host.
    val routingCallFactory: Call.Factory       = koinInject()
    val appScope: CoroutineScope               = koinInject()

    // Register the routing-aware Coil loader exactly once. setSafe is
    // idempotent (it no-ops once a loader exists), but allocating the factory
    // on every AppRoot recompose is wasted work and brushes against coil's
    // "called after the first get()" guard, so gate it behind remember. The
    // calculation runs synchronously in the composition pass -- before any
    // child image composable triggers a get(), which an effect would run after.
    @Suppress("RememberReturnType")
    remember(routingCallFactory) {
        SingletonImageLoader.setSafe { context ->
            pictureLoader(
                context   = context,
                directory = dataDirectory.resolve("cache").resolve("images"),
                calls     = routingCallFactory,
                rechecks  = PictureRechecks(appScope),
                // Pack descriptions carry rows of shields.io badges, and shields.io answers with SVG.
                decoders  = { addPlatformDecoders() },
            )
        }
    }

    WarmLibrary()

    val store: DocumentStore = koinInject()
    val document by store.document.collectAsState()
    val navRequests: NavRequests = koinInject()

    // An interface of a shape this build no longer reads was deleted on the way in, and the standard one is drawn.
    val notifications: NotificationCenter = koinInject()
    val s = nexiraStrings()
    LaunchedEffect(store) {
        if (!store.takeOutdatedNotice()) return@LaunchedEffect
        notifications.push(
            sourceKey = "interface-outdated",
            sender    = Branding.TITLE,
            iconUrl   = null,
            severity  = Severity.Info,
            kind      = Kind.Sticky,
            title     = s.text("interfaceOutdatedReplaced"),
        )
    }

    // ── Background settings ───────────────────────────────────────────────
    val backgroundManager: BackgroundManager = koinInject()
    val backgroundSettings by backgroundManager.settings.collectAsState()
    // Persist background settings debounced and OFF the UI thread: the fx
    // sliders fire per tick, and a synchronous write per tick both janks the
    // drag and multiplies disk writes. The effect restarts on every value
    // change (keyed), so one write lands ~300ms after the drag settles. The
    // manager flushes whatever is still unwritten at shutdown, so a quit or a
    // crash-restart inside the debounce keeps the last slider position.
    LaunchedEffect(backgroundSettings) {
        delay(300.milliseconds)
        withContext(Dispatchers.IO) { backgroundManager.flush() }
    }

    // ── Render: background behind layout ──────────────────────────────────
    val mousePos = remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    var windowSize by remember { mutableStateOf(IntSize.Zero) }
    // What the wallpaper tells the themes module: its overall brightness, for the mode
    // that follows the wallpaper between dark and light, and its colours, which the
    // wallpaper theme is made of. Missing where the document brings no themes module.
    val level = LocalScope.current
    val inputs = remember(level) { level?.launcherServices?.find(ThemeInputs::class) }

    // What the person switched on to look through, if a module brought it. Read where the shell does,
    // so an effect switched on or a module switched off takes hold on the next frame.
    val modules = getKoin().getOrNull<WidgetModules>()
    val effects = modules?.state?.collectAsState()?.value?.effects.orEmpty()
    val effectSettings = modules?.settings?.collectAsState()?.value.orEmpty()
    // Names of a module's widgets in the person's language, which the editor panels read.
    val widgetModules = modules?.state?.collectAsState()?.value?.widgetModules.orEmpty()
    val widgetLabels = remember(widgetModules) { WidgetLabels { widgetModules[it] } }
    // The composition's level, around everything it draws, the editor included.
    ChildLevel(ScopeLevel.Composition) {
    // Where the composition has been, from where the document starts it, as the history
    // module keeps it for the run: an interface that starts over goes on from where it was.
    ProvideCompositionFacts(LauncherConfigs.Composition, document)
    val (history, show) = rememberCompositionHistory(LauncherScreens.Home)
    val back = rememberScopedCommand(HistoryKeys.Back)
    val forward = rememberScopedCommand(HistoryKeys.Forward)
    NavigationInputs(back = { back?.invoke(Unit) }, forward = { forward?.invoke(Unit) })
    // Out-of-composition navigation requests (notification actions, drivers) land in the
    // same history the buttons above drive.
    val latestShow by rememberUpdatedState(show)
    LaunchedEffect(navRequests) {
        navRequests.requests.collect { latestShow(it) }
    }
    CompositionLocalProvider(LocalWidgetLabels provides widgetLabels) {
    ScreenEffectHost(
        effects  = effects,
        settings = effectSettings,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // Base fill behind the wallpaper: while a custom background decodes (or its
                // first video frame arrives) CustomBackground paints nothing, and without
                // this the bare window default -- a flat grey -- shows through. The theme
                // surface is covered edge-to-edge once the image is ready.
                .background(NxColor.page)
                .onSizeChanged { windowSize = it }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull()
                            val touch = change?.type == PointerType.Touch
                            if (event.type == PointerEventType.Move || (touch && event.type == PointerEventType.Press)) {
                                val pos = change?.position
                                if (pos != null && windowSize.width > 0 && windowSize.height > 0) {
                                    mousePos.value = Offset(pos.x / windowSize.width, pos.y / windowSize.height)
                                }
                            } else if (touch && event.type == PointerEventType.Release) {
                                // A finger has no resting place on the glass, so the picture goes back to centre.
                                mousePos.value = Offset(0.5f, 0.5f)
                            }
                        }
                    }
                }
        ) {
          WallpaperLayer(
              settings         = backgroundSettings,
              mousePosProvider = { mousePos.value },
              // Passed on as reported and not from an initial state, which would clear the
              // stored colours on every start before the picture decoded.
              onTone           = { tone: WallpaperTone -> inputs?.wallpaper(tone) },
              // The one setting a player widget can move while the wallpaper is what
              // its transport is pointed at. Through the same state the appearance
              // panel writes, so the two sliders are one value and the debounce above
              // persists it once.
              onAudioVolume    = { volume -> backgroundManager.update { it.copy(audioVolume = volume) } },
          )

          AppLayout(
              history                = history,
              show                   = show,
              backgroundSettings     = backgroundSettings,
              customization          = customization,
              onCustomizationChanged = onCustomizationChanged,
          )

          NotificationStack()

          // The window's open sign-out question, asked by whichever widget offered it.
          SignOutPrompt()
        }
    }
    }
    } // end ChildLevel(Composition)
}
