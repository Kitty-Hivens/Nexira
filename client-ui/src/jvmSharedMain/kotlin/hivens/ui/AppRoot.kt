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
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.annotation.ExperimentalCoilApi
import coil3.network.NetworkFetcher
import coil3.network.cachecontrol.CacheControlCacheStrategy
import hivens.ui.appearance.AppearanceStore
import hivens.ui.background.BackgroundManager
import hivens.ui.background.BackgroundSettings
import hivens.ui.components.SignOutPrompt
import hivens.ui.customization.CustomizationSettings
import hivens.ui.easter.LocalAprilFools
import hivens.ui.navigation.NavRequests
import hivens.ui.notifications.render.NotificationStack
import hivens.ui.theme.NxColor
import hivens.ui.theme.WallpaperTone
import hivens.ui.widgets.ChildLevel
import hivens.ui.widgets.Sources
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.ScopeLevel
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

/** The most the image cache keeps on disk before it evicts the least recently used. */
private const val IMAGE_CACHE_BYTES = 256L * 1024 * 1024

/** Whatever the host feeds into the history besides the screens: thumb buttons on a desktop, the system back gesture on a phone. */
@Composable
internal expect fun NavigationInputs(backStack: NavBackStack)

/** The wallpaper behind the layout, which reports its tone upward. A host with no wallpaper draws nothing and reports none. */
@Composable
internal expect fun WallpaperLayer(
    settings: BackgroundSettings,
    mousePosProvider: () -> Offset,
    onTone: (WallpaperTone) -> Unit,
    onAudioVolume: (Float) -> Unit,
)

/** The image decoders the host adds on top of the common ones. */
internal expect fun ComponentRegistry.Builder.addPlatformDecoders()

/**
 * The launcher's interface inside one window: its composition level, the history,
 * the wallpaper and the layout.
 *
 * The composition's level is made here, around everything it draws. Who the
 * launcher is signed in as and how it looks are read through the sources like any
 * widget reads them, and what the wallpaper shows is handed to the appearance store,
 * which decides the dark scheme and the wallpaper theme from it.
 */
@Composable
fun AppRoot(
    onRealExit: () -> Unit,
    onHideToTray: (() -> Unit)?,
    customization: CustomizationSettings,
    onCustomizationChanged: (CustomizationSettings) -> Unit,
) {
    val appearance: AppearanceStore            = koinInject()
    val dataDirectory: Path                    = koinInject()
    val json: Json                             = koinInject()
    // Smartycraft-routed Call.Factory for Coil's image fetcher. The
    // bypass / direct routing rule lives in Modules.kt alongside the
    // same rule for the Ktor HttpClientProvider; both must agree or
    // news / skin images would diverge from auth and protocol traffic
    // on the same host.
    val routingCallFactory: Call.Factory       = koinInject()
    val af = LocalAprilFools.current

    // Register the routing-aware Coil loader exactly once. setSafe is
    // idempotent (it no-ops once a loader exists), but allocating the factory
    // on every AppRoot recompose is wasted work and brushes against coil's
    // "called after the first get()" guard, so gate it behind remember. The
    // calculation runs synchronously in the composition pass -- before any
    // child image composable triggers a get().
    remember(routingCallFactory) {
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                // In the launcher's own data, not the default under the system temp
                // dir: that one is a tmpfs on many Linux machines, emptied on every
                // boot, so every icon and banner was downloaded again each day, and it
                // is shared with any other program built on the same library.
                .diskCache {
                    DiskCache.Builder()
                        .directory(dataDirectory.resolve("cache").resolve("images").toFile())
                        .maxSizeBytes(IMAGE_CACHE_BYTES)
                        .build()
                }
                .components {
                    // Art on the mirror keeps one address when the picture behind it
                    // changes, and the default strategy never asks the server again,
                    // so an icon cached once stayed for good. This one revalidates by
                    // the ETag and Last-Modified the server sends.
                    add(revalidatingFetcherFactory(routingCallFactory))
                    // Pack descriptions carry rows of shields.io badges, and
                    // shields.io answers with SVG.
                    addPlatformDecoders()
                }
                .build()
        }
    }

    // Navigation history. navigate() pushes detail screens and resets on a
    // top-level destination; back() pops to the actual previous screen instead
    // of a per-screen hardcoded return target.
    val backStack     = remember { NavBackStack(Screen.Home) }

    NavigationInputs(backStack)

    // A made screen deleted while it sits in the history leaves it, back and
    // forward both, so neither arrow can open a page about nothing.
    val screenIds = LocalLayoutGraph.current.screens.map { it.id }.toSet()
    LaunchedEffect(screenIds) {
        backStack.retainWhere(Screen.Home) { it !is Screen.Custom || it.id in screenIds }
    }

    // Out-of-composition navigation requests (notification actions, drivers)
    // land in the same back stack the buttons above drive.
    val navRequests: NavRequests = koinInject()
    LaunchedEffect(navRequests) {
        navRequests.requests.collect { backStack.navigate(it) }
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
    val mousePos    = remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    val mousePxPos  = remember { mutableStateOf(Offset.Zero) }
    var windowSize by remember { mutableStateOf(IntSize.Zero) }
    // What the wallpaper tells the shell: its overall brightness, for the mode that
    // follows the wallpaper between dark and light, and its colours, which the
    // wallpaper theme is made of.
    var tone by remember { mutableStateOf(WallpaperTone.NONE) }

    LaunchedEffect(tone.avgLuminance) { appearance.wallpaperLuminanceChanged(tone.avgLuminance) }

    // What the person switched on to look through, if a module brought it. Read where the shell does,
    // so an effect switched on or a module switched off takes hold on the next frame.
    val modules = getKoin().getOrNull<WidgetModules>()
    val effects = modules?.state?.collectAsState()?.value?.effects.orEmpty()
    val effectSettings = modules?.settings?.collectAsState()?.value.orEmpty()
    // Names of a module's widgets in the person's language, which the editor panels read.
    val widgetStrings = modules?.state?.collectAsState()?.value?.widgetStrings.orEmpty()
    val widgetLabels = remember(widgetStrings) { WidgetLabels { widgetStrings[it] } }
    // The composition's level, around everything it draws, the editor included. The
    // session is read here through the same key a widget reads. Missing, it stays on
    // Loading, and the failure that left it missing has been said out loud already.
    ChildLevel(ScopeLevel.Composition) {
    val appState = rememberScopedSource(Sources.Session).valueOrNull() ?: AppState.Loading
    CompositionLocalProvider(LocalWidgetLabels provides widgetLabels) {
    ScreenEffectHost(
        effects       = effects,
        settings      = effectSettings,
        reducedMotion = customization.reduceMotion,
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
                                    mousePxPos.value = pos
                                    mousePos.value   = Offset(pos.x / windowSize.width, pos.y / windowSize.height)
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
              // Colours are passed on as reported and not from the initial state, which
              // would clear the stored ones on every start before the picture decoded.
              onTone           = { tone = it; appearance.setWallpaperColours(it.colours) },
              // The one setting a player widget can move while the wallpaper is what
              // its transport is pointed at. Through the same state the appearance
              // panel writes, so the two sliders are one value and the debounce above
              // persists it once.
              onAudioVolume    = { volume -> backgroundManager.update { it.copy(audioVolume = volume) } },
          )

          af.WrapContent(
              pixelCursorState = mousePxPos,
              windowSize       = windowSize,
              onRealClose      = onRealExit,
              onHideTray       = onHideToTray,
          ) {
              AppLayout(
                  appState = appState,
                  currentScreen = backStack.current,
                  onScreenChange = backStack::navigate,
                  onSwitchTab = backStack::switchTo,
                  onReplaceScreen = backStack::replaceCurrent,
                  onBack = { backStack.back() },
                  canGoBack = backStack.canGoBack,
                  canGoForward = backStack.canGoForward,
                  onForward = { backStack.forward() },
                  trail = backStack.trail,
                  history = backStack.history,
                  onPopTo = backStack::popTo,
                  backgroundSettings = backgroundSettings,
                  onBackgroundSettingsChanged = { change -> backgroundManager.update(change) },
                  customization              = customization,
                  onCustomizationChanged     = onCustomizationChanged,
              )

              NotificationStack()

              // The window's open sign-out question, asked by whichever widget offered it.
              SignOutPrompt()
          }
        }
    }
    }
    } // end ChildLevel(Composition)
}

/** Experimental in Coil, so the opt-in is kept to this one factory rather than to the whole shell. */
@OptIn(ExperimentalCoilApi::class)
private fun revalidatingFetcherFactory(callFactory: Call.Factory): NetworkFetcher.Factory =
    OkHttpNetworkFetcherFactory(
        callFactory = { callFactory },
        cacheStrategy = { CacheControlCacheStrategy() },
    )
