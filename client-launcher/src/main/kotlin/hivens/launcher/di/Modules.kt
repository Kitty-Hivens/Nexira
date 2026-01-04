package hivens.launcher.di

import hivens.config.AppConfig
import hivens.core.api.AuthService
import hivens.core.api.ServerRepository
import hivens.core.api.SkinRepository
import hivens.core.api.interfaces.*
import hivens.launcher.*
import hivens.launcher.component.EnvironmentPreparer
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Модуль, отвечающий за сетевое взаимодействие.
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
     * HTTP-клиент (OkHttp).
     * SOCKS прокси включен всегда.
     */
    single<OkHttpClient> {
        // Глобальная авторизация для SOCKS (Java API)
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

        // Настройка прокси
        builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(AppConfig.Proxy.HOST, AppConfig.Proxy.PORT)))

        builder.build()
    }

    single<HttpClient> {
        val okHttpInstance = get<OkHttpClient>()

        HttpClient(OkHttp) {
            engine {
                preconfigured = okHttpInstance
            }

            // Плагины Ktor
            install(ContentNegotiation) {
                json(get())
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 600_000 // 10 минут
                connectTimeoutMillis = 30_000  // 30 сек на подключение
                socketTimeoutMillis = 600_000  // 10 минут на ожидание пакетов
            }

            defaultRequest {
                // User-Agent строго по конфигу
                header("User-Agent", "SMARTYlauncher/${AppConfig.LAUNCHER_VERSION}")
                contentType(ContentType.Application.Json)
            }
        }
    }

    // Репозитории
    singleOf(::ServerRepository)
    singleOf(::SkinRepository)
}

/**
 * Модуль основных компонентов приложения.
 */
val appModule = module {
    // AppScope: Жизненный цикл равен времени работы приложения.
    // Используем для запуска игры, чтобы он не прерывался при смене экранов.
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /**
     * Рабочая директория приложения (.aura).
     */
    single(createdAtStart = true) {
        Paths.get(System.getProperty("user.home"), AppConfig.WORK_DIR_NAME)
    }

    // Менеджеры и сервисы
    single { CredentialsManager(get(), get()) }

    single<ISettingsService> {
        val dataDir: java.nio.file.Path = get()
        SettingsService(get(), dataDir.resolve(AppConfig.FILES_SETTINGS))
    }

    single<IFileIntegrityService> { FileIntegrityService() }
    single<IFileDownloadService> { FileDownloadService(get()) }

    single<IManifestProcessorService> { ManifestProcessorService(get()) }
    single { ProfileManager(get(), get()) }
    single { JavaManagerService(get(), get()) }

    // EnvironmentPreparer
    singleOf(::EnvironmentPreparer)

    single<IAuthService> { AuthService(get(), get()) }
    single<IServerListService> { ServerListService(get()) }

    /**
     * Основной сервис запуска.
     * Теперь принимает EnvironmentPreparer через DI.
     */
    single<ILauncherService> {
        LauncherService(
            manifestProcessor = get(),
            profileManager = get(),
            javaManager = get(),
            envPreparer = get()
        )
    }
}
