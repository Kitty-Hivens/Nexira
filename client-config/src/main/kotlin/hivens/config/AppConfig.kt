package hivens.config

/**
 * Основная конфигурация лаунчера.
 * Здесь лежат только настройки логики, сети и инфраструктуры.
 * Никакого UI.
 */
object AppConfig {
    // ==========================================
    // 1. МЕТАДАННЫЕ
    // ==========================================
    const val LAUNCHER_VERSION = "3.6.2"
    const val BRANDING_NAME = "smartycraft"
    const val APP_TITLE = "Aura Launcher"
    const val CLIENT_VERSION = BuildConfig.FORK_VERSION

    // ==========================================
    // 2. СЕТЬ И API
    // ==========================================
    const val BASE_URL = "https://www.smartycraft.ru"
    const val AUTH_URL = "$BASE_URL/launcher2/index.php"
    const val OFFICIAL_JAR_URL = "$BASE_URL/downloads/smartycraft.jar"
    const val SKINS_URL = "$BASE_URL/skins/"

    // Таймауты
    const val TIMEOUT_CONNECT = 30_000L
    const val TIMEOUT_READ = 300_000L

    // ==========================================
    // 3. ФАЙЛОВАЯ СИСТЕМА
    // ==========================================
    const val WORK_DIR_NAME = ".aura"
    const val FILES_SETTINGS = "settings.json"
    const val FILES_PROFILES = "profiles.json"
    const val FILES_HASH_CACHE = "smarty_hash.cache"

    // ==========================================
    // 4. ЛЕГАСИ ПАРАМЕТРЫ (для совместимости с бэком)
    // ==========================================
    const val DEFAULT_SERVER_ID = "Industrial"
    const val DEFAULT_LAUNCHER_HASH = "5515a4bdd5f532faf0db61b8263d1952"
    const val AUTH_SALT = "sdgsdfhgosd8dfrg"
    const val PROTOCOL_DEFAULT_JAR = "smartycraft.jar"
    const val PROTOCOL_DEFAULT_CSUM = "d41d8cd98f00b204e9800998ecf8427e"

    // ==========================================
    // 5. ПРОКСИ
    // ==========================================
    object Proxy {
        const val HOST = "proxy.smartycraft.ru"
        const val PORT = 1080
        const val USER = "proxyuser"
        const val PASS = "proxyuserproxyuser"
    }
}
