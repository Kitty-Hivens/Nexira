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
import hivens.auth.AccountStore
import hivens.auth.AuthProvider
import hivens.auth.AuthProviderRegistry
import hivens.auth.RefreshableAuthProvider
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.PackAuthRequirement
import hivens.core.data.ThemeMode
import hivens.core.diag.ActionRing
import hivens.core.security.SslBypassStore
import hivens.launcher.bootstrap.AutoLoginCoordinator
import hivens.ui.background.BackgroundManager
import hivens.ui.background.BackgroundSettings
import hivens.ui.components.DestructiveConfirmDialog
import hivens.ui.customization.CustomizationSettings
import hivens.ui.easter.LocalAprilFools
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocalStrings
import hivens.ui.navigation.NavRequests
import hivens.ui.notifications.render.NotificationStack
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.NxColor
import hivens.ui.theme.ThemeLibrary
import hivens.ui.theme.WallpaperTone
import hivens.widget.api.LocalLayoutGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import hivens.ui.widgets.modules.LocalWidgetLabels
import hivens.ui.widgets.modules.ScreenEffectHost
import hivens.ui.widgets.modules.WidgetLabels
import hivens.ui.widgets.modules.WidgetModules
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory
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

@Composable
fun AppRoot(
    onWallpaperLuminance: (Float?) -> Unit,
    onWallpaperColours: (List<Int>) -> Unit,
    isDarkTheme: Boolean,
    onRealExit: () -> Unit,
    onHideToTray: (() -> Unit)?,
    onToggleDarkTheme: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    systemThemeAvailable: Boolean,
    themeLibrary: ThemeLibrary,
    onThemeSelected: (String) -> Unit,
    currentLocale: AppLocale,
    onLocaleChanged: (AppLocale) -> Unit,
    customization: CustomizationSettings,
    onCustomizationChanged: (CustomizationSettings) -> Unit,
) {
    val credentialsManager: AccountStore = koinInject()
    val authService: AuthProvider              = koinInject()
    val settingsService: ISettingsService      = koinInject()
    val dataDirectory: Path                    = koinInject()
    val json: Json                             = koinInject()
    val authRegistry: AuthProviderRegistry     = koinInject()
    val bypassStore: SslBypassStore            = koinInject()
    // Present only when a Microsoft client id is configured -- the registry holds
    // the refreshable provider exactly then, so auto-login is gated by its presence.
    val msaProvider: RefreshableAuthProvider?  =
        authRegistry.all.filterIsInstance<RefreshableAuthProvider>().firstOrNull()
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

    var appState      by remember { mutableStateOf<AppState>(AppState.Loading) }
    // Navigation history. navigate() pushes detail screens and resets on a
    // top-level destination; back() pops to the actual previous screen instead
    // of a per-screen hardcoded return target.
    val backStack     = remember { NavBackStack(Screen.Home) }
    var pendingLogout by remember { mutableStateOf(false) }
    // Clearing every account takes the face choice with it: it names a provider
    // that no longer has one, and nothing surfaces the setting again until two
    // accounts are back, so it would decide the face of a session the user set
    // up long after making it.
    //
    // The offline name goes too, whichever identity was fronting the shell. It is
    // the only record of the offline identity, and startup signs that identity
    // back in from it when no account is stored, which after this is always: kept
    // because the face happened to be an online account, it had the next start
    // sign straight back in under a name the user had just signed out of. The
    // offline mode switch stays as it is. It says how to play rather than who
    // plays, and with no name and no account it has nobody to sign in.
    //
    // The store is cleared off the UI thread, since every secret it deletes is a
    // keyring call, and the shell signs out once it has been.
    val logoutScope = rememberCoroutineScope()
    val doLogout: () -> Unit = {
        logoutScope.launch {
            withContext(Dispatchers.IO) {
                credentialsManager.clear()
                settingsService.updateSettings { it.copy(preferredFaceProvider = null, offlinePlayerName = null) }
            }
            appState = AppState.Unauthenticated
        }
    }

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

    // ── Auto-login with offline mode support ──────────────────────────────
    // Business logic lives in AutoLoginCoordinator; the Composable maps the
    // resolution into the local AppState machine. Network-shaped failures
    // retry on a capped backoff for the app's lifetime (a launcher left open
    // signs itself in when the network returns); rejections and missing
    // credentials stop -- looping on those hammers the upstream for nothing.
    // A bypass policy flip restarts the effect for an immediate fresh attempt
    // with a reset ladder (the flip is a user action). A manual login racing
    // the loop wins: the loop re-reads the state each pass, and a pass whose
    // sign-in was already in flight checks it again once the answer is back.
    val autoLoginBypasses by bypassStore.bypasses.collectAsState()
    LaunchedEffect(autoLoginBypasses) {
        var attempt = 0
        while (appState !is AppState.Authenticated) {
            val settings = withContext(Dispatchers.IO) { settingsService.getSettings() }
            val saved = withContext(Dispatchers.IO) {
                credentialsManager.primarySession(settings.preferredFaceProvider)
            }
            val resolution = withContext(Dispatchers.IO) {
                AutoLoginCoordinator.resolveSession(
                    settings     = settings,
                    saved        = saved,
                    authService  = authService,
                    msaProvider  = msaProvider,
                )
            }
            when (resolution) {
                is AutoLoginCoordinator.Resolution.Success -> {
                    val session = resolution.session
                    // A silent MSA refresh rotates the refresh token; persist it so
                    // the next start uses the fresh one instead of re-spending the
                    // stored token. A rotation is not a choice of account, so it never
                    // takes the active slot: it used to, on every start, so "active"
                    // came to mean whichever account the boot happened to refresh, and
                    // a manual sign-in that won the race below lost the slot too.
                    if (session.refreshToken != null && session.refreshToken != saved?.refreshToken) {
                        withContext(Dispatchers.IO) {
                            credentialsManager.saveAccount(
                                session,
                                PackAuthRequirement.Microsoft.PROVIDER_KEY,
                                makeActive = false,
                            )
                        }
                    }
                    // The sign-in that just ran met the 2FA gate, and it is the only
                    // thing that could have told us. Unpersisted, the coordinator would
                    // spend another login on the next start -- and each one invalidates
                    // whatever session the player has in hand.
                    // The sign-in retired the uid the store holds, and whatever is
                    // signed later from the stored account, a skin upload among them,
                    // needs the one it minted.
                    if (resolution.signedIn) {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                credentialsManager.refreshStored(PackAuthRequirement.SmartyCraft.PROVIDER_KEY, session)
                            }.onFailure {
                                LoggerFactory.getLogger("AppShell").warn("Auto-login could not record the refreshed session", it)
                            }
                        }
                    }
                    if (session.twoFactor && saved?.twoFactor != true) {
                        withContext(Dispatchers.IO) {
                            credentialsManager.markTwoFactor(PackAuthRequirement.SmartyCraft.PROVIDER_KEY)
                        }
                        ActionRing.record("Auto-login met the second factor: the SmartyCraft account is marked")
                    }
                    // The form stays usable while a pass is in flight, so the user may
                    // have signed in by hand while this one waited on the network.
                    if (appState is AppState.Authenticated) {
                        ActionRing.record("Auto-login answered after a manual sign-in; the manual one stays")
                        return@LaunchedEffect
                    }
                    appState = AppState.Authenticated(session)
                    return@LaunchedEffect
                }
                AutoLoginCoordinator.Resolution.NoCredentials,
                AutoLoginCoordinator.Resolution.Rejected,
                // The certificate decision belongs to the user. Dropping to the
                // login form routes them to the prompt that asks for it, which
                // also grants the bypass and restores silent auto-login from the
                // next start.
                AutoLoginCoordinator.Resolution.CertificateUntrusted -> {
                    appState = AppState.Unauthenticated
                    return@LaunchedEffect
                }
                AutoLoginCoordinator.Resolution.NetworkDown -> {
                    // The startup spinner covers only the first attempt; after it
                    // the login form is usable while the loop retries silently.
                    if (appState is AppState.Loading) appState = AppState.Unauthenticated
                    val delayMs = AutoLoginCoordinator.retryDelayMs(attempt)
                    attempt += 1
                    ActionRing.record("Auto-login: network down, retry #$attempt in ${delayMs / 1000}s")
                    delay(delayMs.milliseconds)
                }
            }
        }
    }

    // ── Render: background behind layout ──────────────────────────────────
    val mousePos    = remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    val mousePxPos  = remember { mutableStateOf(Offset.Zero) }
    var windowSize by remember { mutableStateOf(IntSize.Zero) }
    // What the wallpaper tells the shell: its overall brightness, for the mode that
    // follows the wallpaper between dark and light, and its colours, which the
    // wallpaper theme is made of.
    var tone by remember { mutableStateOf(WallpaperTone.NONE) }

    LaunchedEffect(tone.avgLuminance) { onWallpaperLuminance(tone.avgLuminance) }

    // What the person switched on to look through, if a module brought it. Read where the shell does,
    // so an effect switched on or a module switched off takes hold on the next frame.
    val modules = getKoin().getOrNull<WidgetModules>()
    val effects = modules?.state?.collectAsState()?.value?.effects.orEmpty()
    val effectSettings = modules?.settings?.collectAsState()?.value.orEmpty()
    // Names of a module's widgets in the person's language, which the editor panels read.
    val widgetStrings = modules?.state?.collectAsState()?.value?.widgetStrings.orEmpty()
    val widgetLabels = remember(widgetStrings) { WidgetLabels { widgetStrings[it] } }
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
              onTone           = { tone = it; onWallpaperColours(it.colours) },
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
                  onLogin = { session -> appState = AppState.Authenticated(session) },
                  onLogout = { pendingLogout = true },
                  isDarkTheme = isDarkTheme,
                  onToggleDarkTheme = onToggleDarkTheme,
                  themeMode = themeMode,
                  onThemeModeChanged = onThemeModeChanged,
                  systemThemeAvailable = systemThemeAvailable,
                  themeLibrary = themeLibrary,
                  onThemeSelected = onThemeSelected,
                  currentLocale = currentLocale,
                  onLocaleChanged = onLocaleChanged,
                  backgroundSettings = backgroundSettings,
                  onBackgroundSettingsChanged = { change -> backgroundManager.update(change) },
                  customization              = customization,
                  onCustomizationChanged     = onCustomizationChanged,
              )

              NotificationStack()

              if (pendingLogout) {
                  val s = LocalStrings.current
                  DestructiveConfirmDialog(
                      title        = s.logoutConfirmTitle,
                      body         = s.logoutConfirmBody,
                      confirmLabel = s.navLogout,
                      onConfirm    = doLogout,
                      onDismiss    = { pendingLogout = false },
                  )
              }
              // Automation bypass for the now two-step logout (request -> confirm).
              PuppetClick("logout.confirm") { doLogout() }
          }
        }
    }
    }
}

/** Experimental in Coil, so the opt-in is kept to this one factory rather than to the whole shell. */
@OptIn(ExperimentalCoilApi::class)
private fun revalidatingFetcherFactory(callFactory: Call.Factory): NetworkFetcher.Factory =
    OkHttpNetworkFetcherFactory(
        callFactory = { callFactory },
        cacheStrategy = { CacheControlCacheStrategy() },
    )
