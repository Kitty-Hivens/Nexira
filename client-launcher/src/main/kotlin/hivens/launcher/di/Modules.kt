package hivens.launcher.di

import hivens.config.AppConfig
import hivens.core.api.AuthService
import hivens.core.api.HttpClientProvider
import hivens.core.api.PlayerRepository
import hivens.core.api.ServerRepository
import hivens.core.api.SkinRepository
import hivens.core.api.interfaces.*
import hivens.launcher.*
import hivens.launcher.component.EnvironmentPreparer
import hivens.launcher.platform.PlatformPaths
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

    /**
     * Secure HTTP client (OkHttp).
     * SOCKS proxy is always enabled.
     */
    single<OkHttpClient> {
        // Global authorization for SOCKS (Java API)
        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
            override fun getPasswordAuthentication(): java.net.PasswordAuthentication {
                return java.net.PasswordAuthentication(
                    AppConfig.Proxy.USER,
                    AppConfig.Proxy.PASS.toCharArray()
                )
            }
        })

        OkHttpClient.Builder()
            .connectTimeout(AppConfig.TIMEOUT_CONNECT, TimeUnit.MILLISECONDS)
            .readTimeout(AppConfig.TIMEOUT_READ, TimeUnit.MILLISECONDS)
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(AppConfig.Proxy.HOST, AppConfig.Proxy.PORT)))
            .build()
    }

    /**
     * Insecure HTTP client (SSL verification disabled).
     * Registered only for the explicit "connect anyway" user flow.
     * Never injected by default — must be requested by named("insecure").
     */
    single<OkHttpClient>(named("insecure")) {
        val (socketFactory, trustManager) = buildTrustAllSsl()

        OkHttpClient.Builder()
            .connectTimeout(AppConfig.TIMEOUT_CONNECT, TimeUnit.MILLISECONDS)
            .readTimeout(AppConfig.TIMEOUT_READ, TimeUnit.MILLISECONDS)
            .sslSocketFactory(socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(AppConfig.Proxy.HOST, AppConfig.Proxy.PORT)))
            .build()
    }

    /**
     * [HttpClientProvider] — thin wrapper that resolves the correct [HttpClient]
     * on every request via [NetworkState.sslBypassEnabled].
     *
     * Injected into all repositories instead of [HttpClient] directly,
     * so that SSL bypass takes effect immediately on the next network call
     * without requiring Koin singleton recreation.
     */
    single {
        val secure   = buildHttpClient(get<OkHttpClient>(),                get())
        val insecure = buildHttpClient(get<OkHttpClient>(named("insecure")), get())
        HttpClientProvider {
            if (NetworkState.sslBypassEnabled) insecure else secure
        }
    }

    /**
     * Named insecure [HttpClientProvider] for [AuthService] — always uses
     * the insecure client regardless of [NetworkState], because it is
     * injected specifically for the "connect anyway" login retry path.
     */
    single<HttpClientProvider>(named("insecure")) {
        val insecure = buildHttpClient(get<OkHttpClient>(named("insecure")), get())
        HttpClientProvider { insecure }
    }

    // Repositories
    single { ServerRepository(get(), get(), get<java.nio.file.Path>().toFile()) }
    singleOf(::SkinRepository)
    singleOf(::PlayerRepository)
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
    single { CredentialsManager(get(), get()) }

    single<ISettingsService> {
        val dataDir: java.nio.file.Path = get()
        SettingsService(get(), dataDir.resolve(AppConfig.FILES_SETTINGS))
    }

    single<IFileDownloadService> { FileDownloadService(get()) }

    single<IManifestProcessorService> { ManifestProcessorService(get()) }
    single { ProfileManager(get(), get()) }
    single { JavaManagerService(get(), get()) }

    // EnvironmentPreparer
    singleOf(::EnvironmentPreparer)

    single<IAuthService> { AuthService(get(), get()) }

    /**
     * Insecure [IAuthService] — used exclusively for the SSL bypass login retry.
     * Always connects without certificate verification.
     */
    single<IAuthService>(named("insecure")) {
        AuthService(get(named("insecure")), get())
    }

    single<IServerListService> { ServerListService(get()) }

    /**
     * Basic launch service.
     * Accepts EnvironmentPreparer via DI.
     */
    single<ILauncherService> {
        LauncherService(
            manifestProcessor = get(),
            profileManager    = get(),
            javaManager       = get(),
            envPreparer       = get()
        )
    }

    // Update Service
    single {
        UpdateService(
            clientProvider = get(),
            json           = get(),
            dataDirectory  = get()
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

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
            header("User-Agent", "SMARTYlauncher/${AppConfig.LAUNCHER_VERSION}")
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
