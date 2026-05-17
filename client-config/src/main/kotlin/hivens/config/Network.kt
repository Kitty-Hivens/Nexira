package hivens.config

/**
 * Process-wide network timeouts shared between every OkHttp / Ktor client
 * the launcher builds.
 *
 * Pre-Conduit Phase 3 this object also held SmartyCraft-specific URLs and
 * SOCKS proxy credentials as `const val`s. Those moved into
 * `hivens.launcher.network.ServerProtocolConfig` (Conduit Phase 3, 2.2.14)
 * so Mirror / staging operators can point the launcher at a different host
 * via `<dataDir>/server-config.json` or `-Daura.conduit.baseurl=...` without
 * recompiling. The KDoc on [ServerProtocolConfig] documents the override
 * contract; this file now only owns values that genuinely do NOT vary
 * between deployments.
 *
 * ── Routing channels ──
 *
 * The launcher exposes two HTTP channels, wired in `client-launcher` DI.
 * Pick the one that matches *what* you are talking to, not *where* you
 * live in code:
 *
 *   - Smartycraft channel (default `HttpClientProvider`) -- routed through
 *     [ServerProtocolConfig]'s SOCKS proxy. Required for everything on
 *     `*.smartycraft.ru`: auth, dashboard, server manifests, skins, player
 *     lookups, client files.
 *
 *   - Direct channel (`HttpClientProvider` qualified `named("direct")`) --
 *     no proxy. For third-party CDNs that don't care about SMARTYcraft and
 *     have their own TLS: GitHub releases (auto-update), BellSoft JDKs,
 *     Maven Central LWJGL natives.
 *
 * Adding a new outbound call? Decide which channel applies and inject the
 * matching provider. Don't construct a fresh HttpClient -- that bypasses
 * both the per-host SSL-bypass state and any future routing changes here.
 */
object Network {
    /** OkHttp connect-timeout, applied to every client variant. */
    const val TIMEOUT_CONNECT = 30_000L

    /** OkHttp read-timeout, applied to every client variant. */
    const val TIMEOUT_READ    = 300_000L
}
