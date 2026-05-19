package hivens.launcher.network

import hivens.config.ExperimentalConduitOverride
import java.net.URI
import kotlinx.serialization.Serializable

/**
 * Resolved configuration for talking to a SmartyCraft-compatible backend.
 *
 * Replaces the mix of `Network.BASE_URL`, `Network.AUTH_URL`,
 * `Network.OFFICIAL_JAR_URL`, and `Network.Proxy.*` constants that used to
 * be `const val` baked at build time. Conduit Phase 3 pulls them into a
 * single config object so:
 *
 *   - The launcher can be pointed at a Mirror (Project Mirror, post-Void)
 *     without recompiling -- drop a `<dataDir>/server-config.json` with the
 *     mirror's URL, restart.
 *   - Per-server differences (test/staging environments, regional mirrors)
 *     are first-class data, not tribal knowledge in scattered constants.
 *   - Tests can spin up a mock SmartycraftV1Protocol pointed at a
 *     local fixture server.
 *
 * ## Defaults
 *
 * All fields default to the production SmartyCraft values inherited from
 * `Network` constants. A vanilla Aura install with no config file behaves
 * identically to the pre-Conduit baseline.
 *
 * ## Override paths (opt-in via [ExperimentalConduitOverride])
 *
 * 1. **Config file** at `<dataDir>/server-config.json` -- full data class
 *    serialised. Loader merges with defaults so partial files are fine.
 * 2. **System property** `aura.conduit.baseurl` -- overrides just the
 *    base URL, leaves everything else at config-file or default.
 *    Useful for one-off "test against mirror right now" CLI workflows.
 *
 * Both override paths are guarded by [ExperimentalConduitOverride] --
 * normal launcher behavior reads through to the data class fields directly.
 */
@Serializable
data class ServerProtocolConfig(
    /**
     * Origin of the SmartyCraft API -- `https://www.smartycraft.ru` for
     * production. No trailing slash. Derived URLs ([authUrl],
     * [officialJarUrl], [clientFilesBase]) append paths.
     */
    val baseUrl: String = DEFAULT_BASE_URL,

    /** SOCKS5 proxy hostname used for the proxy fallback channel. */
    val proxyHost: String = DEFAULT_PROXY_HOST,

    /** SOCKS5 proxy port. */
    val proxyPort: Int = DEFAULT_PROXY_PORT,

    /** SOCKS5 proxy username. Public per-protocol value; not a secret. */
    val proxyUser: String = DEFAULT_PROXY_USER,

    /**
     * SOCKS5 proxy password. Recovered from smrt-deco -- public per-protocol
     * value, not a secret. See `feedback_secrets_vs_interop`.
     */
    val proxyPass: String = DEFAULT_PROXY_PASS,

    /**
     * OkHttp `connectTimeout`, applied to every client variant the launcher
     * builds (direct, proxied, insecure). Bumpable for high-latency Mirror
     * operators via `server-config.json`.
     */
    val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,

    /** OkHttp `readTimeout`, same per-Mirror override path as [connectTimeoutMs]. */
    val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
) {
    /** POST endpoint for `action=login`/`action=loader`/etc. */
    val authUrl: String get() = "$baseUrl/launcher2/index.php"

    /** Latest official launcher binary -- used by [hivens.launcher.protocol.LauncherHashCache]. */
    val officialJarUrl: String get() = "$baseUrl/downloads/smartycraft.jar"

    /** Per-server client-file CDN root -- `FileDownloadService` appends `/<server>/<file>`. */
    val clientFilesBase: String get() = "$baseUrl/launcher/clients"

    /**
     * Hostname used as the key in [hivens.launcher.network.NetworkState]'s per-host
     * SSL-bypass set. Extracted from [baseUrl]'s URI authority so a Mirror
     * operator pointing the launcher at `https://mirror.example.com` keys
     * bypasses against `mirror.example.com`, not the production smartycraft
     * host. Falls back to the default-base-url host when [baseUrl] is
     * malformed -- callers always get a usable host string instead of an
     * empty match key.
     */
    val sslBypassHost: String get() =
        runCatching { URI(baseUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: run { URI(DEFAULT_BASE_URL).host!! }

    companion object {
        const val DEFAULT_BASE_URL    = "https://www.smartycraft.ru"
        const val DEFAULT_PROXY_HOST  = "proxy.smartycraft.ru"
        const val DEFAULT_PROXY_PORT  = 58613
        const val DEFAULT_PROXY_USER  = "smartycraftproxyuser"
        const val DEFAULT_PROXY_PASS  = "ngyxvpFfiUz4FB2OPx1nqEa4TEKigbKc"
        const val DEFAULT_CONNECT_TIMEOUT_MS = 30_000L
        const val DEFAULT_READ_TIMEOUT_MS    = 300_000L

        /** System-property name for the runtime base-URL override. */
        const val SYSTEM_PROP_BASE_URL = "aura.conduit.baseurl"

        /**
         * Resolve the effective config given a parsed-from-file value. Applies
         * the [SYSTEM_PROP_BASE_URL] override on top if present.
         */
        @ExperimentalConduitOverride
        fun resolve(loaded: ServerProtocolConfig = ServerProtocolConfig()): ServerProtocolConfig {
            val override = System.getProperty(SYSTEM_PROP_BASE_URL)?.takeIf { it.isNotBlank() }
                ?: return loaded
            return loaded.copy(baseUrl = override.trimEnd('/'))
        }
    }
}
