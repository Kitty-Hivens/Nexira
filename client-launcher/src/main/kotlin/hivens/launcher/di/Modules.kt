package hivens.launcher.di

import hivens.config.AppConfig
import hivens.core.api.AuthService
import hivens.core.api.PlayerRepository
import hivens.core.api.ServerRepository
import hivens.core.api.SkinRepository
import hivens.core.api.interfaces.*
import hivens.launcher.*
import hivens.launcher.component.EnvironmentPreparer
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
import org.koin.dsl.module
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Paths
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
     * HTTP client (OkHttp).
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

        val builder = OkHttpClient.Builder()
            .connectTimeout(AppConfig.TIMEOUT_CONNECT, TimeUnit.MILLISECONDS)
            .readTimeout(AppConfig.TIMEOUT_READ, TimeUnit.MILLISECONDS)

        // Proxy setting
        builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(AppConfig.Proxy.HOST, AppConfig.Proxy.PORT)))

        builder.build()
    }

    single<HttpClient> {
        val okHttpInstance = get<OkHttpClient>()

        HttpClient(OkHttp) {
            engine {
                preconfigured = okHttpInstance
            }

            // Ktor plugins
            install(ContentNegotiation) {
                json(get())
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 600_000 // 10 minutes
                connectTimeoutMillis = 30_000 // 30 seconds to connect
                socketTimeoutMillis = 600_000 // 10 minutes to wait for packets
            }

            defaultRequest {
                // User-Agent strictly according to the config
                header("User-Agent", "SMARTYlauncher/${AppConfig.LAUNCHER_VERSION}")
                contentType(ContentType.Application.Json)
            }
        }
    }

    // Repositories
    singleOf(::ServerRepository)
    singleOf(::SkinRepository)
    singleOf(::PlayerRepository)
}

/**
 * Module of the main components of the application.
 */
val appModule = module {
    /**
     * Application working directory (.aura).
     */
    single(createdAtStart = true) {
        Paths.get(System.getProperty("user.home"), AppConfig.APP_DIR)
    }

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
    single<IServerListService> { ServerListService(get()) }

    /**
     * Basic launch service.
     * Now accepts EnvironmentPreparer via DI.
     */
    single<ILauncherService> {
        LauncherService(
            manifestProcessor = get(),
            profileManager = get(),
            javaManager = get(),
            envPreparer = get()
        )
    }

    // Update Service
    single {
        UpdateService(
            httpClient = get(),
            json = get(),
            dataDirectory = get()
        )
    }
}
