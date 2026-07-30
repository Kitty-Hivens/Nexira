package hivens.launcher.network

import hivens.config.ExperimentalConduitOverride
import java.net.URI
import kotlinx.serialization.Serializable

/**
 * Resolved configuration for talking to a SmartyCraft-compatible
 * backend. All fields default to production SmartyCraft values; a
 * vanilla Nexira install with no config file behaves like the baseline.
 *
 * Override paths (opt-in via [ExperimentalConduitOverride]):
 * 1. Config file at `<dataDir>/server-config.json` -- the full data
 *    class serialized; loader merges with defaults so partial files
 *    are fine.
 * 2. System property `nexira.conduit.baseurl` -- overrides just the
 *    base URL, leaves everything else at config-file or default.
 *    Useful for one-off "test against mirror right now" CLI workflows.
 *
 * Normal launcher behavior reads through to the data class fields
 * directly; both override paths are opt-in.
 */
@Serializable
data class ServerProtocolConfig(
    /**
     * Origin of the SmartyCraft API -- `https://www.smartycraft.ru` for
     * production. No trailing slash. Derived URLs ([authUrl],
     * [officialJarUrl], [clientFilesBase]) append paths.
     */
    val baseUrl: String = DEFAULT_BASE_URL,

    /**
     * OkHttp `connectTimeout`, applied to every client variant the launcher
     * builds (direct, insecure). Bumpable for high-latency Mirror operators
     * via `server-config.json`.
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
        const val DEFAULT_CONNECT_TIMEOUT_MS = 30_000L
        const val DEFAULT_READ_TIMEOUT_MS    = 300_000L

        /** System-property name for the runtime base-URL override. */
        const val SYSTEM_PROP_BASE_URL = "nexira.conduit.baseurl"

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
