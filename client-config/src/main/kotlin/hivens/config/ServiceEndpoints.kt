package hivens.config

/**
 * Конфигурация конечных точек API.
 * Используется для изоляции URL-адресов проекта, повышения переносимости.
 */
object ServiceEndpoints {
    /** Базовый домен для API и CDN. */
    const val BASE_URL = "https://www.smartycraft.ru"

    /** Endpoint для аутентификации пользователя (POST). */
    // Он же используется для uploadAsset в протоколе Scold
    const val AUTH_LOGIN = "$BASE_URL/launcher2/index.php"

    /** Ссылка на официальный JAR (для проверки хеша/обновления). */
    const val OFFICIAL_JAR_URL = "$BASE_URL/downloads/smartycraft.jar"

    /** Настройки SOCKS5 прокси. */
    const val PROXY_HOST = "proxy.smartycraft.ru"
    const val PROXY_PORT = 1080
}
