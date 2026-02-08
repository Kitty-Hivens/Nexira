package hivens.config

/**
 * Basic launcher configuration.
 * Here are only the logic, network and infrastructure settings.
 * No UI.
 */
object AppConfig {
    // ==========================================
    // 1. METADATA
    // ==========================================
    const val LAUNCHER_VERSION = "3.6.3"
    const val BRANDING_NAME = "smartycraft"
    const val APP_TITLE = "Aura Launcher"
    const val CLIENT_VERSION = BuildConfig.FORK_VERSION

    // ==========================================
    // 2. NETWORK & API
    // ==========================================
    const val BASE_URL = "https://www.smartycraft.ru"
    const val AUTH_URL = "$BASE_URL/launcher2/index.php"
    const val OFFICIAL_JAR_URL = "$BASE_URL/downloads/smartycraft.jar"

    // Timeouts
    const val TIMEOUT_CONNECT = 30_000L
    const val TIMEOUT_READ = 300_000L

    // ==========================================
    // 3. FILE SYSTEM
    // ==========================================
    const val APP_DIR = ".aura"
    const val FILES_SETTINGS = "settings.json"
    const val FILES_PROFILES = "profiles.json"
    const val FILES_HASH_CACHE = "smarty_hash.cache"

    // ==========================================
    // 4. LEGACY PARAMETERS (for compatibility with backend)
    // ==========================================
    const val DEFAULT_SERVER_ID = "Industrial"
    const val DEFAULT_LAUNCHER_HASH = "0714d6ea824454d0af31a02373eef703"
    const val AUTH_SALT = "sdgsdfhgosd8dfrg"
    const val PROTOCOL_DEFAULT_JAR = "smartycraft.jar"
    const val PROTOCOL_DEFAULT_CSUM = "d41d8cd98f00b204e9800998ecf8427e"

    // ==========================================
    // 5. PROXY
    // ==========================================
    object Proxy {
        const val HOST = "proxy.smartycraft.ru"
        const val PORT = 1080
        const val USER = "proxyuser"
        const val PASS = "proxyuserproxyuser"
    }
}
