package hivens.launcher.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import hivens.config.ServiceEndpoints
import hivens.core.api.AuthService
import hivens.core.api.SmartyNetworkService
import hivens.core.api.interfaces.*
import hivens.launcher.*
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Модуль, отвечающий за сетевое взаимодействие, HTTP-клиенты и парсеры.
 */
val networkModule = module {

    /**
     * Предоставляет глобальный экземпляр [Gson] с включенным форматированием.
     */
    single<Gson> {
        GsonBuilder().setPrettyPrinting().create()
    }

    /**
     * Предоставляет основной [OkHttpClient], настроенный для работы через прокси SmartyCraft.
     * Включает настройки таймаутов и аутентификации прокси.
     */
    single<OkHttpClient> {
        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication("proxyuser", "proxyuserproxyuser".toCharArray())
            }
        })

        val okHttpProxyAuthenticator = Authenticator { _, response ->
            val credential = Credentials.basic("proxyuser", "proxyuserproxyuser")
            response.request.newBuilder()
                .header("Proxy-Authorization", credential)
                .build()
        }

        OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(ServiceEndpoints.PROXY_HOST, ServiceEndpoints.PROXY_PORT)))
            .proxyAuthenticator(okHttpProxyAuthenticator)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    singleOf(::SmartyNetworkService)
}

/**
 * Модуль основных компонентов приложения: сервисов, менеджеров и конфигураций.
 */
val appModule = module {

    /**
     * Рабочая директория приложения (.aura).
     */
    single(createdAtStart = true) {
        Paths.get(System.getProperty("user.home"), ".aura")
    }

    single { CredentialsManager(get(), get()) }

    single<ISettingsService> {
        val dataDir: java.nio.file.Path = get()
        SettingsService(get(), dataDir.resolve("settings.json"))
    }

    single<IFileIntegrityService> { FileIntegrityService() }
    single<IFileDownloadService> { FileDownloadService(get(), get()) }
    single<IManifestProcessorService> { ManifestProcessorService(get()) }
    single { ProfileManager(get(), get()) }
    single { JavaManagerService(get(), get()) }
    single<IAuthService> { AuthService(get(), get()) }
    single<IServerListService> { ServerListService(get()) }

    /**
     * Основной сервис запуска игры, агрегирующий логику подготовки окружения.
     */
    single<ILauncherService> {
        LauncherService(
            manifestProcessor = get(),
            profileManager = get(),
            javaManager = get()
        )
    }
}
