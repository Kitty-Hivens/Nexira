package hivens.launcher.di

import hivens.config.ServiceEndpoints
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
import kotlinx.serialization.json.Json
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

    /**
     * Глобальная конфигурация JSON.
     */
    single<Json> {
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            isLenient = true
            encodeDefaults = true
        }
    }

    /**
     * Основной HTTP-клиент.
     * Использует движок OkHttp под капотом для поддержки SOCKS прокси с авторизацией.
     */
    single<HttpClient> {
        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
            override fun getPasswordAuthentication(): java.net.PasswordAuthentication {
                // TODO: В будущем вынести логин/пароль в конфиг
                return java.net.PasswordAuthentication("proxyuser", "proxyuserproxyuser".toCharArray())
            }
        })

        HttpClient(OkHttp) {
            // Настройка движка (специфично для JVM/OkHttp)
            engine {
                config {
                    // Настройка прокси
                    proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(ServiceEndpoints.PROXY_HOST, ServiceEndpoints.PROXY_PORT)))

                    // Аутентификация на прокси
                    proxyAuthenticator { _, response ->
                        val credential = okhttp3.Credentials.basic("proxyuser", "proxyuserproxyuser")
                        response.request.newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build()
                    }

                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(300, TimeUnit.SECONDS)
                }
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
                // Эмуляция User-Agent, чтобы сервер не отверг запрос
                header("User-Agent", "SMARTYlauncher/3.6.2")
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

    /**
     * Рабочая директория приложения (.aura).
     */
    single(createdAtStart = true) {
        Paths.get(System.getProperty("user.home"), ".aura")
    }

    // Менеджеры и сервисы
    single { CredentialsManager(get(), get()) }

    single<ISettingsService> {
        val dataDir: java.nio.file.Path = get()
        SettingsService(get(), dataDir.resolve("settings.json"))
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
