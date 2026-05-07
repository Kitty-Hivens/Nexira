package hivens.config

/**
 * HTTP endpoints, timeouts, and the SOCKS proxy the launcher tunnels through.
 * Everything talking to smartycraft.ru lives here.
 */
object Network {
    const val BASE_URL = "https://www.smartycraft.ru"
    const val AUTH_URL = "$BASE_URL/launcher2/index.php"

    /**
     * Official SMARTYcraft launcher JAR — used by [hivens.core.api.ServerRepository]
     * to refresh `Protocol.DEFAULT_LAUNCHER_HASH` when the server replies with
     * `status: "UPDATE"`. Removing this file from the upstream site will break
     * the dashboard handshake.
     */
    const val OFFICIAL_JAR_URL = "$BASE_URL/downloads/smartycraft.jar"

    const val TIMEOUT_CONNECT = 30_000L
    const val TIMEOUT_READ    = 300_000L

    /**
     * Hardcoded SOCKS proxy that the upstream service expects every client
     * to tunnel through. The credentials are part of the protocol (recovered
     * from the decompiled official launcher) — they are public by definition,
     * not project secrets.
     */
    object Proxy {
        const val HOST = "proxy.smartycraft.ru"
        const val PORT = 1080
        const val USER = "proxyuser"
        const val PASS = "proxyuserproxyuser"
    }
}
