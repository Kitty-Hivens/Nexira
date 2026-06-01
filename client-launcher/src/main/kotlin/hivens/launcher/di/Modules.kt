package hivens.launcher.di

import hivens.config.Protocol
import hivens.config.Storage
import hivens.core.api.AuthService
import hivens.launcher.network.ChannelRouter
import hivens.launcher.network.NetworkState
import hivens.launcher.network.ServerProtocolConfig
import hivens.launcher.network.ServerProtocolConfigLoader
import hivens.launcher.protocol.LauncherHashCache
import hivens.launcher.protocol.SmartycraftV1Protocol
import hivens.core.api.HttpClientProvider
import hivens.core.api.PlayerRepository
import hivens.core.api.ServerRepository
import hivens.core.api.SkinRepository
import hivens.core.api.interfaces.*
import hivens.core.security.IKeyringStorage
import hivens.launcher.*
import hivens.launcher.component.ClasspathProvider
import hivens.launcher.component.EnvironmentPreparer
import hivens.launcher.component.GameCommandBuilder
import hivens.launcher.component.ProcessLogHandler
import hivens.launcher.launch.LauncherController
import hivens.launcher.mrpack.MrpackInstaller
import hivens.launcher.platform.PlatformPaths
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.runtime.loader.FabricLikeResolver
import hivens.launcher.runtime.loader.ForgeLegacyResolver
import hivens.launcher.runtime.loader.ForgeResolver
import hivens.launcher.runtime.loader.LoaderRegistry
import hivens.launcher.runtime.loader.ModernInstallerResolver
import hivens.launcher.security.KeyringStorageFactory
import hivens.launcher.smrt.ModIconResolver
import hivens.launcher.smrt.OpenSmrtHelperResolver
import hivens.launcher.smrt.SmartyModPlanner
import hivens.launcher.smrt.SmrtPackClient
import hivens.launcher.smrt.SmrtSyncService
import hivens.launcher.update.UpdateApplicators
import hivens.launcher.update.UpdateService
import hivens.widget.model.DefaultLayout
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import java.net.Authenticator
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.nio.file.Path
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Module responsible for network interaction.
 */
val networkModule = module {

    single<Json> {
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            isLenient = true
            encodeDefaults = true
            // Coerce unknown enum values to the field's default instead
            // of throwing. Without this, downgrading the launcher to a
            // build that does not yet declare a recently-added enum
            // variant (e.g. HomeView.New written by a newer build, read
            // by an older one) blows up SettingsService.reload() and
            // SilentlyResetsEverything to defaults -- the user loses
            // every other setting because of one unknown value.
            coerceInputValues = true
        }
    }

    // ── Smartycraft channel ───────────────────────────────────────────────────
    // SOCKS-proxied. Required for everything on `*.smartycraft.ru`. See the
    // routing taxonomy in [HttpClientProvider]'s KDoc.

    /**
     * Smartycraft secure client. SSL verification on, SOCKS proxy always on.
     * Backs the default (smartycraft) [HttpClientProvider]. Proxy creds and
     * host/port come from [ServerProtocolConfig] (Conduit Phase 3) so a
     * Mirror server with different proxy infra can plug in via config file.
     */
    single<OkHttpClient> {
        val cfg: ServerProtocolConfig = get()

        // Authenticator.setDefault is JVM-wide; SOCKS5 auth has no per-client
        // hook (OkHttp delegates SOCKS connect to JDK SocksSocketImpl which
        // only consults the global Authenticator). Scope the response so the
        // creds never leak to a third-party HTTP/HTTPS proxy that an unrelated
        // JVM caller might be talking to -- only this exact SOCKS host/port
        // gets answered.
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                if (requestorType != RequestorType.PROXY) return null
                if (requestingHost != cfg.proxyHost) return null
                if (requestingPort != cfg.proxyPort) return null
                return PasswordAuthentication(cfg.proxyUser, cfg.proxyPass.toCharArray())
            }
        })

        OkHttpClient.Builder()
            .connectTimeout(cfg.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(cfg.readTimeoutMs, TimeUnit.MILLISECONDS)
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(cfg.proxyHost, cfg.proxyPort)))
            .build()
    }

    /**
     * Smartycraft insecure client (SSL verification disabled).
     * Registered only for the explicit "connect anyway" user flow.
     * Never injected by default -- must be requested by `named("insecure")`.
     *
     * The default `HttpClientProvider` already returns this insecure
     * client when `NetworkState.bypassFor()` is true (see
     * `single<HttpClientProvider>` below), so a caller that has just
     * granted bypass via `NetworkState.grantBypass()` can switch back
     * to the regular `authService` and reach the same transport. The
     * `named("insecure")` chain (this client + dependent ChannelRouter
     * / IServerProtocol / IAuthService) is therefore redundant for the
     * standard "Connect anyway" flow and is slated for removal once
     * the UI call sites are migrated. Until then it stays -- still
     * useful as a one-shot insecure transport that doesn't require
     * touching `NetworkState`.
     */
    single<OkHttpClient>(named("insecure")) {
        val cfg: ServerProtocolConfig = get()
        val (socketFactory, trustManager) = buildTrustAllSsl()

        OkHttpClient.Builder()
            .connectTimeout(cfg.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(cfg.readTimeoutMs, TimeUnit.MILLISECONDS)
            .sslSocketFactory(socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(cfg.proxyHost, cfg.proxyPort)))
            .build()
    }

    // ── Direct channel ────────────────────────────────────────────────────────
    // No proxy, strict TLS. For third-party CDNs (GitHub releases, BellSoft
    // JDKs, Maven Central). Survives any SMARTYcraft proxy outage by design --
    // the auto-updater must keep working when the upstream proxy doesn't.

    /**
     * Direct-channel client. No proxy, no SSL bypass. Backs the
     * [HttpClientProvider] qualified `named("direct")`.
     *
     * SSL bypass is intentionally not honored here: the third-party CDNs
     * we hit on this channel have rock-solid TLS, and silently widening the
     * bypass to them just because the user accepted it for smartycraft.ru
     * would be a needless trust expansion.
     */
    single<OkHttpClient>(named("direct")) {
        val cfg: ServerProtocolConfig = get()
        OkHttpClient.Builder()
            .connectTimeout(cfg.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(cfg.readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * Default (smartycraft) [HttpClientProvider] -- thin wrapper that resolves
     * the correct channel + cert mode on every request.
     *
     * Per-request decision (mirrors [ChannelRouter] for AuthService):
     *   - SSL bypass active for the smartycraft host -> insecure (proxy + no TLS check)
     *   - forceProxyMode toggle on -> secure proxy
     *   - default -> direct (no proxy)
     *
     * Pre-fix the provider always returned a proxy-only client regardless of
     * the user's force-proxy toggle, so `SkinManager` and `FileDownloadService`
     * never honored the setting -- the Settings switch only affected auth
     * routing via ChannelRouter, while skin and client-file traffic stayed
     * pinned to the SOCKS hop. Users whose network couldn't reach
     * `proxy.smartycraft.ru:58613` saw login work (direct-first via
     * ChannelRouter) but skins / news images / pack syncs fail silently.
     * Now every smartycraft.ru request reads [NetworkState] freshly, so the
     * Settings toggle takes effect on the next call without a relaunch.
     */
    single {
        val cfg: ServerProtocolConfig = get()
        val direct   = buildHttpClient(get<OkHttpClient>(named("direct")),   get())
        val secure   = buildHttpClient(get<OkHttpClient>(),                  get())
        val insecure = buildHttpClient(get<OkHttpClient>(named("insecure")), get())
        HttpClientProvider {
            when {
                NetworkState.bypassFor(cfg.sslBypassHost) -> insecure
                NetworkState.forceProxyMode()             -> secure
                else                                      -> direct
            }
        }
    }

    /**
     * Direct-channel [HttpClientProvider]. Inject this (`named("direct")`)
     * for any outbound call that does NOT need to tunnel through the
     * SMARTYcraft proxy -- see routing notes in [HttpClientProvider].
     */
    single<HttpClientProvider>(named("direct")) {
        val direct = buildHttpClient(get<OkHttpClient>(named("direct")), get())
        HttpClientProvider { direct }
    }

    /**
     * Smartycraft-routed `okhttp3.Call.Factory` for callers that consume the
     * OkHttp call API directly (Coil's image fetcher today; no Ktor [HttpClient]
     * adapter on its side). Mirrors the per-request channel decision the
     * default smartycraft [HttpClientProvider] makes; both must agree, or
     * Nexira's news strip and skin images would route differently from the
     * auth / protocol traffic that uses [HttpClientProvider].
     *
     * Keeping the two implementations in one file makes the divergence
     * surface concrete: any future change to the routing rule touches both
     * adjacent registrations under one diff.
     */
    single<Call.Factory> {
        val cfg: ServerProtocolConfig = get()
        val direct   = get<OkHttpClient>(named("direct"))
        val secure   = get<OkHttpClient>()
        val insecure = get<OkHttpClient>(named("insecure"))
        Call.Factory { request ->
            val client = when {
                NetworkState.bypassFor(cfg.sslBypassHost) -> insecure
                NetworkState.forceProxyMode()             -> secure
                else                                      -> direct
            }
            client.newCall(request)
        }
    }

    // ── Conduit (network refactor) ──────────────────────────────────────────
    // IServerProtocol abstracts all `*.smartycraft.ru` traffic so repositories
    // don't know URL paths or `action=` strings. ChannelRouter is the
    // direct-default + proxy-fallback gate (Conduit Phase 2):
    //
    // Default channel: ChannelRouter wraps a direct + a proxied OkHttpClient.
    // Each call tries direct first; on IOException retries via proxy. Users in
    // censored networks toggle Settings -> Network -> "Force proxy mode" to
    // skip the direct attempt (see NetworkState.forceProxyMode).
    //
    // Insecure channel: separate IServerProtocol bound for SSL-bypass login
    // retry -- uses a router that wraps the insecure-direct + insecure-proxy
    // pair so the bypass survives the fallback chain.
    //
    // Wire spec lives in docs/dev/smartycraft-v1-protocol.md.

    single<ChannelRouter> {
        ChannelRouter(
            direct = buildHttpClient(get<OkHttpClient>(named("direct")), get()),
            proxy  = buildHttpClient(get<OkHttpClient>(),                 get()),
        )
    }
    single<ChannelRouter>(named("insecure")) {
        // Insecure direct + insecure proxy. Used when user has accepted SSL
        // bypass for the host; fallback still works, just without TLS check.
        val cfg: ServerProtocolConfig = get()
        val (socketFactory, trustManager) = buildTrustAllSsl()
        val insecureDirect = OkHttpClient.Builder()
            .connectTimeout(cfg.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(cfg.readTimeoutMs, TimeUnit.MILLISECONDS)
            .sslSocketFactory(socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
        ChannelRouter(
            direct = buildHttpClient(insecureDirect,                       get()),
            proxy  = buildHttpClient(get<OkHttpClient>(named("insecure")), get()),
        )
    }

    // ServerProtocolConfig -- Conduit Phase 3. Loads from
    // <dataDir>/server-config.json with smartycraft.ru defaults if absent.
    // Optional system-property override aura.conduit.baseurl gates a runtime
    // base URL change for Mirror development / test environments (gated by
    // ExperimentalConduitOverride opt-in inside the loader).
    single<ServerProtocolConfig> {
        ServerProtocolConfigLoader(get()).load(get<Path>())
    }

    single { LauncherHashCache(
        dataDir = get<Path>().toFile(),
        router  = get<ChannelRouter>(),
        config  = get<ServerProtocolConfig>(),
    ) }

    single<IServerProtocol> {
        SmartycraftV1Protocol(get<ChannelRouter>(), get(), get<LauncherHashCache>(), get<ServerProtocolConfig>())
    }
    single<IServerProtocol>(named("insecure")) {
        SmartycraftV1Protocol(
            get<ChannelRouter>(named("insecure")),
            get(),
            get<LauncherHashCache>(),
            get<ServerProtocolConfig>(),
        )
    }

    // Repositories -- thin adapters over IServerProtocol.
    single { ServerRepository(get<IServerProtocol>()) }
    single { SkinRepository(get<IServerProtocol>()) }
    single { PlayerRepository(get<IServerProtocol>()) }
}

val appModule = module {
    /**
     * Per-OS application paths. See [PlatformPaths] for layout.
     */
    single(createdAtStart = true) { PlatformPaths.system() }

    /**
     * Application data directory. Resolved via [PlatformPaths] so that all
     * subsystems (settings, profiles, credentials, downloaded clients,
     * skin cache, logs, crash reports) share one platform-correct root.
     */
    single<Path>(createdAtStart = true) { get<PlatformPaths>().dataDir }

    /**
     * Crash report generator + dialog presenter. Main.kt constructs its
     * own instance pre-Koin for the uncaught-exception handler; this
     * registration covers post-Koin consumers (none today, but the
     * dependency contract makes it injectable for future Composables
     * that want to trigger a manual report).
     */
    single { CrashReporter(get()) }

    // IKeyringStorage picked at startup via KeyringStorageFactory.system()
    // -- libsecret on Linux, Credential Manager / DPAPI on Windows,
    // Keychain on macOS, NoOp fallback when no daemon is reachable.
    // CredentialsManager handles the file-fallback path internally when
    // keyring.store() returns false, so this single line wires both
    // the happy and the degraded path.
    single<IKeyringStorage> {
        KeyringStorageFactory.system()
    }
    single { CredentialsManager(get(), get(), get()) }

    single<ISettingsService> {
        val dataDir: Path = get()
        SettingsService(get(), dataDir.resolve(Storage.SETTINGS_FILE))
    }

    // Replays persisted user-experimental overrides (forceProxyMode,
    // mimicVersionOverride) into their respective global state holders
    // on Koin start. `createdAtStart = true` makes this run during
    // `startKoin { modules(...) }` so the values are live before the
    // first protocol call.
    single(createdAtStart = true) { SettingsRestoreHook(get()) }

    /**
     * Process-lifetime coroutine scope for fire-and-forget background
     * work (tray-launch flow, AutoSync, `LauncherController.launch`).
     * SupervisorJob so a single failed child doesn't take down the
     * rest. Single shared scope across the whole launcher so the JVM
     * shutdown hook installed by [AppCoroutineScopeHook] cancels every
     * coroutine on process exit.
     */
    single<CoroutineScope>(createdAtStart = true) {
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )
    }

    // Installs the shutdown hook that cancels the scope above. Separated
    // from the scope's own factory so the factory stays a one-liner and
    // the hook can be tested independently if needed.
    single(createdAtStart = true) { AppCoroutineScopeHook(get()) }

    /**
     * Launch-flow orchestrator. Consumes only `client-core` interfaces
     * + the shared coroutine scope, so it sits cleanly on the
     * client-launcher side of the module layering -- no UI types
     * (i18n strings, console service) leak in.
     */
    singleOf(::LauncherController)

    single {
        val dataDir: Path = get()
        ProtectedPaths(dataDir.resolve(Storage.PROTECTED_PATHS_FILE), get())
    }
    single {
        val dataDir: Path = get()
        ManifestCache(dataDir.resolve("manifest-cache"), get())
    }
    single<IFileDownloadService> { FileDownloadService(get(), get(), get(), get<ServerProtocolConfig>()) }

    // Hivens Mirror sync. Uses the "direct" HttpClient because
    // smrt.hivens.dev and Modrinth are public CDN-fronted endpoints
    // that don't need the SC channel's SOCKS proxy or SSL bypass.
    // Always wired so toggling on at runtime requires no graph rebuild.
    single { SmrtPackClient(get(named("direct"))) }
    single { SmrtSyncService(get(), get()) }

    // Smarty -> open-smrt-network swap. Direct channel: GitHub releases +
    // raw.githubusercontent.com are public, no SC proxy. The planner is what
    // both sync paths (LauncherController, AutoSyncService) consult.
    single { OpenSmrtHelperResolver(get(named("direct")), get(), get()) }
    single { SmartyModPlanner(get<OpenSmrtHelperResolver>()::resolve, get()) }
    single { PackInstaller(syncService = get(), runtimeProvisioner = get(), repository = get(), dataDir = get()) }
    single {
        MrpackInstaller(
            clientProvider = get(named("direct")),
            json = get(),
            javaManager = get(),
            runtimeProvisioner = get(),
            repository = get(),
            dataDir = get(),
        )
    }

    // Per-mod icon URL resolver for the Library PackDetail Content tab.
    // Direct iconUrl wins; otherwise resolves a Modrinth project's icon
    // via SmrtPackClient. Results cached per project_id inside the
    // resolver instance.
    single {
        val client: SmrtPackClient = get()
        ModIconResolver { projectId ->
            client.resolveModrinthProject(projectId).iconUrl
        }
    }

    single<IManifestProcessorService> { ManifestProcessorService(get()) }
    single { ProfileManager(get(), get()) }
    // Direct channel -- BellSoft JDK CDN does not require the SMARTYcraft proxy.
    single<IJavaManager> { JavaManagerService(get(), get(named("direct"))) }

    // Launch pipeline collaborators
    // Direct channel -- Maven Central LWJGL/JInput natives don't need the proxy.
    single { EnvironmentPreparer(get(named("direct"))) }
    single { ClasspathProvider(get()) }
    single { GameCommandBuilder(get()) }
    single { ProcessLogHandler() }

    // Canonical runtime provisioner -- vanilla + loader libraries from the
    // official Mojang/Forge CDNs into the shared roots. Direct channel: these
    // CDNs do not use the SMARTYcraft proxy (same rationale as JavaManagerService).
    single { ForgeLegacyResolver(get(named("direct")), get()) }
    single {
        // Modern loaders run the official installer headless, caching its
        // output here so re-launches skip the multi-minute install.
        val loaderCacheDir: Path = get<Path>().resolve("loader-cache")
        LoaderRegistry(
            listOf(
                // "forge" routes to legacy (<=1.12.2) or the modern installer by MC version.
                ForgeResolver(
                    legacy = get<ForgeLegacyResolver>(),
                    modern = ModernInstallerResolver.forge(get(named("direct")), get(), get(), loaderCacheDir),
                ),
                ModernInstallerResolver.neoforge(get(named("direct")), get(), get(), loaderCacheDir),
                FabricLikeResolver(get(named("direct")), get(), "fabric", FabricLikeResolver.FABRIC_META),
                FabricLikeResolver(get(named("direct")), get(), "quilt", FabricLikeResolver.QUILT_META),
            ),
        )
    }
    single {
        RuntimeProvisioner(
            librariesDir = get<PlatformPaths>().librariesDir,
            assetsDir = get<PlatformPaths>().assetsDir,
            clientProvider = get(named("direct")),
            json = get(),
            loaderRegistry = get(),
        )
    }

    single<IAuthService> { AuthService(get<IServerProtocol>()) }

    /**
     * Insecure [IAuthService] -- used exclusively for the SSL bypass login retry.
     * Always connects without certificate verification (via the insecure-channel
     * IServerProtocol variant bound above in coreModule).
     */
    single<IAuthService>(named("insecure")) {
        AuthService(get<IServerProtocol>(named("insecure")))
    }

    // Cache feeds the tray menu's first published DBusMenu layout before
    // the live fetch returns -- see [ServerListCacheStore] KDoc for the
    // "(No servers)" placeholder bug it fixes.
    single<ServerListCacheStore> {
        val dataDir: Path = get()
        JsonServerListCacheStore(
            file = dataDir.resolve(Storage.SERVERS_CACHE_FILE),
            json = get(),
        )
    }

    single<IServerListService> { SmartyCraftServerListService(get(), get(), get()) }

    // JSON-on-disk pack registry. Persists installed PackInstances
    // to <dataDir>/packs.json so Library reflects real state across
    // launches. Empty file -> empty list (cold start UX is the
    // Library Empty CTA pointing at Browse).
    single<IPackRepository> {
        val dataDir: Path = get()
        JsonPackRepository(
            file = dataDir.resolve(Storage.PACKS_FILE),
            json = get(),
        )
    }

    // Widget layout graph persistence (Phase 1 / kernel-2). Default
    // graph lives at /widget/default-layout.json inside :widget-api;
    // first run seeds the file, thereafter the on-disk copy is the
    // source of truth. Reactive via StateFlow so the future editor
    // mutates the graph live.
    single {
        val dataDir: Path = get()
        LayoutGraphRepository(
            file         = dataDir.resolve(Storage.LAYOUT_GRAPH_FILE),
            json         = get(),
            scope        = get(),
            defaultGraph = { DefaultLayout.load(get()) },
        )
    }

    // Flush pending debounced layout writes on JVM shutdown. Lives as
    // its own createdAtStart=true single so the hook is registered
    // during startKoin{}. Runs in parallel with AppCoroutineScopeHook
    // (JVM shutdown hooks run concurrently); flush() is mutex-locked
    // and cancellation-safe, so racing with scope cancellation is OK.
    single(createdAtStart = true) { LayoutGraphFlushHook(get()) }

    single {
        val dataDir: Path = get()
        val profiles: ProfileManager = get()
        val credentials: CredentialsManager = get()
        val settings: ISettingsService = get()
        AutoSyncService(
            authService = get(),
            downloadService = get(),
            manifestProcessor = get(),
            manifestCache = get(),
            dataDirectory = dataDir,
            credentialsProvider = { credentials.load() },
            optionalModsStateProvider = { serverId ->
                profiles.getProfile(serverId).optionalModsState
            },
            smartyPlanner = get(),
            settingsProvider = { settings.getSettings() },
        )
    }

    /**
     * Basic launch service. All collaborators are constructor-injected so the
     * facade is fully replaceable / mockable in tests.
     */
    single<ILauncherService> {
        LauncherService(
            profileManager     = get(),
            javaManager        = get(),
            envPreparer        = get(),
            classpathProvider  = get(),
            commandBuilder     = get(),
            logHandler         = get(),
            runtimeProvisioner = get(),
            sharedAssetsDir    = get<PlatformPaths>().assetsDir,
            sharedLibrariesDir = get<PlatformPaths>().librariesDir,
        )
    }

    // Update Service -- direct channel. GitHub releases must remain reachable
    // even when the SMARTYcraft proxy is down, otherwise the auto-updater
    // cannot ship the very fix that restores proxy connectivity.
    single {
        UpdateService(
            clientProvider  = get(named("direct")),
            json            = get(),
            dataDirectory   = get(),
            settingsService = get()
        )
    }

    // Per-platform update applicator selected at startup. Kept as a singleton
    // so the shutdown hook each implementation registers fires exactly once.
    single<IUpdateApplicator> { UpdateApplicators.forCurrentPlatform() }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Wraps the given [OkHttpClient] in a Ktor [HttpClient] with our shared timeouts, headers, and JSON content-negotiation. */
private fun buildHttpClient(okHttpInstance: OkHttpClient, json: Json): HttpClient =
    HttpClient(OkHttp) {
        engine { preconfigured = okHttpInstance }

        install(ContentNegotiation) { json(json) }

        install(HttpTimeout) {
            requestTimeoutMillis = 600_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis  = 600_000
        }

        defaultRequest {
            header("User-Agent", "SMARTYlauncher/${Protocol.MIMIC_LAUNCHER_VERSION}")
            contentType(ContentType.Application.Json)
        }
    }

/**
 * Builds a trust-all SSL socket factory for the insecure client.
 * Allows connecting to servers with expired certificates
 * when the user explicitly accepts the risk.
 */
private fun buildTrustAllSsl(): Pair<SSLSocketFactory, X509TrustManager> {
    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<X509Certificate>, authType: String
        ) = Unit
        override fun checkServerTrusted(
            chain: Array<X509Certificate>, authType: String
        ) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, arrayOf(trustManager), SecureRandom())
    return ctx.socketFactory to trustManager
}
