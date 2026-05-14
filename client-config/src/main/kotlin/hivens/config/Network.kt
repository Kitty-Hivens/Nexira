package hivens.config

/**
 * HTTP endpoints, timeouts, and the SOCKS proxy the launcher tunnels through.
 * Everything talking to smartycraft.ru lives here.
 *
 * ── Routing channels ──────────────────────────────────────────────────────────
 *
 * The launcher exposes two HTTP channels, wired in `client-launcher` DI. Pick
 * the one that matches *what* you are talking to, not *where* you live in code:
 *
 * ◆ Smartycraft channel (default `HttpClientProvider`)
 *     Routed through [Proxy]. Required for everything on `*.smartycraft.ru`:
 *     auth, dashboard, server manifests, skins, player lookups, client files.
 *     If [Proxy] credentials rotate, every call on this channel breaks until
 *     the launcher ships an update with refreshed creds.
 *
 * ❖ Direct channel (`HttpClientProvider` qualified `named("direct")`)
 *     No proxy. For third-party CDNs that don't care about SMARTYcraft and
 *     have their own TLS we can trust: GitHub releases (auto-update),
 *     download.bell-sw.com (BellSoft JDKs), repo1.maven.org (LWJGL natives).
 *     Survives any SMARTYcraft outage — by design, because the auto-updater
 *     must keep working when the upstream proxy doesn't.
 *
 * Adding a new outbound call? Decide which channel applies and inject the
 * matching provider. Don't construct a fresh HttpClient — that bypasses both
 * the global SSL-bypass flag and any future routing changes here.
 */
object Network {
    @Deprecated(
        message = "Use ServerProtocolConfig.baseUrl injected via DI — supports config file + system-property override per Conduit Phase 3. Will be removed in 2.2.14.",
        level = DeprecationLevel.WARNING,
    )
    const val BASE_URL = "https://www.smartycraft.ru"

    @Deprecated(
        message = "Use ServerProtocolConfig.authUrl injected via DI per Conduit Phase 3. Will be removed in 2.2.14.",
        level = DeprecationLevel.WARNING,
    )
    @Suppress("DEPRECATION")
    const val AUTH_URL = "$BASE_URL/launcher2/index.php"

    /**
     * Host key used when granting / checking SSL-bypass via
     * `NetworkState.bypassFor()`. Matches the certificate-validation
     * target (the TLS server, not the SOCKS proxy in front of it).
     * If we ever broaden to multiple smartycraft subdomains the
     * NetworkState API can grow `bypassFor` to a pattern match; today
     * only this one host ever has a reason to bypass.
     */
    const val SSL_BYPASS_HOST = "www.smartycraft.ru"

    /**
     * Official SMARTYcraft launcher JAR — used by [hivens.core.api.ServerRepository]
     * to refresh `Protocol.DEFAULT_LAUNCHER_HASH` when the server replies with
     * `status: "UPDATE"`. Removing this file from the upstream site will break
     * the dashboard handshake.
     */
    @Deprecated(
        message = "Use ServerProtocolConfig.officialJarUrl injected via DI per Conduit Phase 3. Will be removed in 2.2.14.",
        level = DeprecationLevel.WARNING,
    )
    @Suppress("DEPRECATION")
    const val OFFICIAL_JAR_URL = "$BASE_URL/downloads/smartycraft.jar"

    const val TIMEOUT_CONNECT = 30_000L
    const val TIMEOUT_READ    = 300_000L

    /**
     * Force HTTP/1.1 on the smartycraft channel.
     *
     * okhttp negotiates HTTP/2 by default when the server advertises ALPN
     * support. h2 multiplexing over a SOCKS proxy with long-running response
     * bodies (auth payloads, file manifest sync) periodically dies with
     * `Connection reset` mid-stream — observed in the wild on the SMARTYcraft
     * channel. HTTP/1.1 with parallel connections trades multiplexing for
     * resilience to mid-stream resets and is the safer default while we
     * don't control the proxy or the upstream server.
     *
     * The direct channel (GitHub, BellSoft, Maven Central) is unaffected —
     * those endpoints have rock-solid h2 stacks and no SOCKS hop.
     */
    const val FORCE_HTTP1_FOR_SMARTYCRAFT = true

    /**
     * Hardcoded SOCKS proxy that the upstream service expects every client
     * to tunnel through. The credentials are part of the protocol (recovered
     * from the decompiled official launcher) — they are public by definition,
     * not project secrets.
     */
    @Deprecated(
        message = "Use ServerProtocolConfig.proxyHost/Port/User/Pass injected via DI per Conduit Phase 3. Will be removed in 2.2.14.",
        level = DeprecationLevel.WARNING,
    )
    object Proxy {
        const val HOST = "proxy.smartycraft.ru"
        const val PORT = 58613
        const val USER = "smartycraftproxyuser"
        const val PASS = "ngyxvpFfiUz4FB2OPx1nqEa4TEKigbKc"
    }
}
