package hivens.launcher.di

import hivens.config.Network
import hivens.config.Protocol
import hivens.config.Storage
import hivens.core.api.AuthService
import hivens.core.api.interfaces.IServerProtocol
import hivens.launcher.protocol.LauncherHashCache
import hivens.launcher.protocol.SmartycraftV1Protocol
import hivens.core.api.HttpClientProvider
import hivens.core.api.PlayerRepository
import hivens.core.api.ServerRepository
import hivens.core.api.SkinRepository
import hivens.core.api.interfaces.*
import hivens.launcher.*
import hivens.launcher.component.ClasspathProvider
import hivens.launcher.component.EnvironmentPreparer
import hivens.launcher.component.GameCommandBuilder
import hivens.launcher.component.ProcessLogHandler
import hivens.launcher.platform.PlatformPaths
import hivens.launcher.update.UpdateApplicators
import hivens.launcher.update.UpdateService
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol as OkProtocol
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

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
        }
    }

    // ── Smartycraft channel ───────────────────────────────────────────────────
    // SOCKS-proxied. Required for everything on `*.smartycraft.ru`. See the
    // routing taxonomy in `hivens.config.Network`.

    /**
     * Smartycraft secure client. SSL verification on, SOCKS proxy always on.
     * Backs the default (smartycraft) [HttpClientProvider].
     */
    single<OkHttpClient> {
        // Global authorization for SOCKS (Java API)
        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
            override fun getPasswordAuthentication(): java.net.PasswordAuthentication {
                return java.net.PasswordAuthentication(
                    Network.Proxy.USER,
                    Network.Proxy.PASS.toCharArray()
                )
            }
        })

        OkHttpClient.Builder()
            .connectTimeout(Network.TIMEOUT_CONNECT, TimeUnit.MILLISECONDS)
            .readTimeout(Network.TIMEOUT_READ, TimeUnit.MILLISECONDS)
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(Network.Proxy.HOST, Network.Proxy.PORT)))
            .applySmartycraftProtocols()
            .build()
    }

    /**
     * Smartycraft insecure client (SSL verification disabled).
     * Registered only for the explicit "connect anyway" user flow.
     * Never injected by default — must be requested by named("insecure").
     */
    single<OkHttpClient>(named("insecure")) {
        val (socketFactory, trustManager) = buildTrustAllSsl()

        OkHttpClient.Builder()
            .connectTimeout(Network.TIMEOUT_CONNECT, TimeUnit.MILLISECONDS)
            .readTimeout(Network.TIMEOUT_READ, TimeUnit.MILLISECONDS)
            .sslSocketFactory(socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(Network.Proxy.HOST, Network.Proxy.PORT)))
            .applySmartycraftProtocols()
            .build()
    }

    // ── Direct channel ────────────────────────────────────────────────────────
    // No proxy, strict TLS. For third-party CDNs (GitHub releases, BellSoft
    // JDKs, Maven Central). Survives any SMARTYcraft proxy outage by design —
    // the auto-updater must keep working when the upstream proxy doesn't.

    /**
     * Direct-channel client. No proxy, no SSL bypass. Backs the
     * [HttpClientProvider] qualified `named("direct")`.
     *
     * SSL bypass is intentionally not honoured here: the third-party CDNs
     * we hit on this channel have rock-solid TLS, and silently widening the
     * bypass to them just because the user accepted it for smartycraft.ru
     * would be a needless trust expansion.
     */
    single<OkHttpClient>(named("direct")) {
        OkHttpClient.Builder()
            .connectTimeout(Network.TIMEOUT_CONNECT, TimeUnit.MILLISECONDS)
            .readTimeout(Network.TIMEOUT_READ, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * Default (smartycraft) [HttpClientProvider] — thin wrapper that resolves
     * the correct proxied [HttpClient] on every request via
     * [NetworkState.sslBypassEnabled].
     *
     * Injected into all smartycraft.ru-bound repositories instead of [HttpClient]
     * directly, so that SSL bypass takes effect immediately on the next network
     * call without requiring Koin singleton recreation.
     */
    single {
        val secure   = buildHttpClient(get<OkHttpClient>(),                get())
        val insecure = buildHttpClient(get<OkHttpClient>(named("insecure")), get())
        HttpClientProvider {
            // Per-host SSL bypass with expiry (Vault #2). Default channel
            // talks only to *.smartycraft.ru, so the single host check is
            // sufficient — direct-channel hosts (GitHub, BellSoft, Maven
            // Central) have their own provider and never bypass.
            if (NetworkState.bypassFor(Network.SSL_BYPASS_HOST)) insecure else secure
        }
    }

    /**
     * Named insecure [HttpClientProvider] for [AuthService] — always uses
     * the insecure smartycraft client regardless of [NetworkState], because it
     * is injected specifically for the "connect anyway" login retry path.
     */
    single<HttpClientProvider>(named("insecure")) {
        val insecure = buildHttpClient(get<OkHttpClient>(named("insecure")), get())
        HttpClientProvider { insecure }
    }

    /**
     * Direct-channel [HttpClientProvider]. Inject this (`named("direct")`)
     * for any outbound call that does NOT need to tunnel through the
     * SMARTYcraft proxy — see routing notes in `hivens.config.Network`.
     */
    single<HttpClientProvider>(named("direct")) {
        val direct = buildHttpClient(get<OkHttpClient>(named("direct")), get())
        HttpClientProvider { direct }
    }

    // ── Conduit (network refactor) ──────────────────────────────────────────
    // IServerProtocol abstracts all `*.smartycraft.ru` traffic so repositories
    // don't know URL paths or `action=` strings. Two bound variants:
    //   - default: routes through whatever HttpClientProvider's `current` is
    //     (today: SOCKS proxy; will become direct-with-fallback in Conduit Phase 2)
    //   - named("insecure"): for the SSL-bypass login retry path; same
    //     protocol shape, different underlying HTTP client.
    // Wire spec lives in docs/dev/smartycraft-v1-protocol.md.

    single { LauncherHashCache(get<java.nio.file.Path>().toFile(), get<HttpClientProvider>()) }

    single<IServerProtocol> {
        SmartycraftV1Protocol(get<HttpClientProvider>(), get(), get<LauncherHashCache>())
    }
    single<IServerProtocol>(named("insecure")) {
        SmartycraftV1Protocol(
            get<HttpClientProvider>(named("insecure")),
            get(),
            get<LauncherHashCache>(),
        )
    }

    // Repositories — thin adapters over IServerProtocol post-Conduit Phase 1.
    single { ServerRepository(get<IServerProtocol>()) }
    single { SkinRepository(get<IServerProtocol>()) }
    single { PlayerRepository(get<IServerProtocol>()) }
}

/**
 * Module of the main components of the application.
 */
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
    single<java.nio.file.Path>(createdAtStart = true) { get<PlatformPaths>().dataDir }

    // Managers and services
    //
    // IKeyringStorage chosen at startup via KeyringStorageFactory.system()
    // — Linux libsecret on this platform, NoOp fallback elsewhere or when
    // the daemon is unreachable. CredentialsManager handles the file-fallback
    // path internally when keyring.store() returns false, so this single
    // line wires both the happy path and the degraded path.
    single<hivens.core.security.IKeyringStorage> {
        hivens.launcher.security.KeyringStorageFactory.system()
    }
    single { CredentialsManager(get(), get(), get()) }

    single<ISettingsService> {
        val dataDir: java.nio.file.Path = get()
        SettingsService(get(), dataDir.resolve(Storage.SETTINGS_FILE))
    }

    single {
        val dataDir: java.nio.file.Path = get()
        ProtectedPaths(dataDir.resolve(Storage.PROTECTED_PATHS_FILE), get())
    }
    single {
        val dataDir: java.nio.file.Path = get()
        ManifestCache(dataDir.resolve("manifest-cache"), get())
    }
    single<IFileDownloadService> { FileDownloadService(get(), get(), get()) }

    single<IManifestProcessorService> { ManifestProcessorService(get()) }
    single { ProfileManager(get(), get()) }
    // Direct channel — BellSoft JDK CDN does not require the SMARTYcraft proxy.
    single<IJavaManager> { JavaManagerService(get(), get(named("direct"))) }

    // Launch pipeline collaborators
    // Direct channel — Maven Central LWJGL/JInput natives don't need the proxy.
    single { EnvironmentPreparer(get(named("direct"))) }
    single { ClasspathProvider(get()) }
    single { GameCommandBuilder() }
    single { ProcessLogHandler() }

    single<IAuthService> { AuthService(get<IServerProtocol>()) }

    /**
     * Insecure [IAuthService] — used exclusively for the SSL bypass login retry.
     * Always connects without certificate verification (via the insecure-channel
     * IServerProtocol variant bound above in coreModule).
     */
    single<IAuthService>(named("insecure")) {
        AuthService(get<IServerProtocol>(named("insecure")))
    }

    single<IServerListService> { ServerListService(get()) }

    single {
        val dataDir: java.nio.file.Path = get()
        val profiles: ProfileManager = get()
        val credentials: CredentialsManager = get()
        AutoSyncService(
            authService = get(),
            downloadService = get(),
            manifestProcessor = get(),
            dataDirectory = dataDir,
            credentialsProvider = { credentials.load() },
            optionalModsStateProvider = { serverId ->
                profiles.getProfile(serverId).optionalModsState
            },
        )
    }

    /**
     * Basic launch service. All collaborators are constructor-injected so the
     * facade is fully replaceable / mockable in tests.
     */
    single<ILauncherService> {
        LauncherService(
            profileManager    = get(),
            javaManager       = get(),
            envPreparer       = get(),
            classpathProvider = get(),
            commandBuilder    = get(),
            logHandler        = get()
        )
    }

    // Update Service — direct channel. GitHub releases must remain reachable
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

/**
 * Pins the smartycraft channel to HTTP/1.1 when [Network.FORCE_HTTP1_FOR_SMARTYCRAFT]
 * is true. h2 multiplexing over the SOCKS hop drops mid-stream on long bodies;
 * 1.1 with parallel connections trades multiplexing for resilience. Skipped on
 * the direct channel — its third-party CDN endpoints have rock-solid h2 stacks.
 *
 * Qodana correctly notices the flag is currently always-true ([Network.FORCE_HTTP1_FOR_SMARTYCRAFT]
 * is `const val true`), making the `else this` branch dead at compile time. The
 * branch stays on purpose — it's a kill-switch for the day h2-over-SOCKS
 * starts behaving (or for someone debugging whether the pin is what's
 * causing a new symptom). Suppression below is the explicit "yes, on purpose".
 */
@Suppress("KotlinConstantConditions")
private fun OkHttpClient.Builder.applySmartycraftProtocols(): OkHttpClient.Builder =
    if (Network.FORCE_HTTP1_FOR_SMARTYCRAFT) protocols(listOf(OkProtocol.HTTP_1_1)) else this

/**
 * Builds an [HttpClient] backed by the given [OkHttpClient].
 * Extracted to eliminate duplication between secure and insecure variants.
 */
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
private fun buildTrustAllSsl(): Pair<javax.net.ssl.SSLSocketFactory, javax.net.ssl.X509TrustManager> {
    val trustManager = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<java.security.cert.X509Certificate>, authType: String
        ) = Unit
        override fun checkServerTrusted(
            chain: Array<java.security.cert.X509Certificate>, authType: String
        ) = Unit
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }
    val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
    ctx.init(null, arrayOf(trustManager), java.security.SecureRandom())
    return ctx.socketFactory to trustManager
}
