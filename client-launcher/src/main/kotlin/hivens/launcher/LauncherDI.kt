package hivens.launcher

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import hivens.core.api.*
import hivens.core.api.interfaces.*
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.nio.file.Path
import java.nio.file.Paths

class LauncherDI {

    val dataDirectory: Path
    val httpClient: OkHttpClient
    private val gson: Gson

    // Сервисы
    val authService: IAuthService
    private val integrityService: IFileIntegrityService
    val downloadService: IFileDownloadService
    val manifestProcessorService: IManifestProcessorService
    val launcherService: ILauncherService
    val serverListService: IServerListService
    val settingsService: ISettingsService
    val profileManager: ProfileManager
    val javaManagerService: JavaManagerService
    val credentialsManager: CredentialsManager
    val smartyNetworkService: SmartyNetworkService

    init {
        val userHome = System.getProperty("user.home")
        this.dataDirectory = Paths.get(userHome, ".aura")
        this.gson = GsonBuilder().setPrettyPrinting().create()

        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication("proxyuser", "proxyuserproxyuser".toCharArray())
            }
        })

        val okHttpProxyAuthenticator = okhttp3.Authenticator { _, response ->
            val credential = Credentials.basic("proxyuser", "proxyuserproxyuser")
            response.request.newBuilder()
                .header("Proxy-Authorization", credential)
                .build()
        }

        this.httpClient = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("proxy.smartycraft.ru", 1080)))
            .proxyAuthenticator(okHttpProxyAuthenticator)
            .build()

        // Инициализация сервисов
        this.authService = AuthService(httpClient, gson)
        this.integrityService = FileIntegrityService()
        this.downloadService = FileDownloadService(httpClient, gson)
        this.manifestProcessorService = ManifestProcessorService(gson)
        this.settingsService = SettingsService(gson, dataDirectory.resolve("settings.json"))
        this.credentialsManager = CredentialsManager(dataDirectory, gson)
        this.smartyNetworkService = SmartyNetworkService(httpClient, gson)
        this.serverListService = ServerListService(smartyNetworkService)
        this.profileManager = ProfileManager(dataDirectory, gson)
        this.javaManagerService = JavaManagerService(dataDirectory, httpClient)
        this.launcherService = LauncherService(
            manifestProcessorService,
            profileManager,
            javaManagerService
        )
    }
}
