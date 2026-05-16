package hivens.core.api

import io.ktor.client.HttpClient

/**
 * Delegates to the appropriate [HttpClient] on every request.
 * The selection logic is provided by the caller via [selector] lambda,
 * keeping this class free of any launcher-specific dependencies.
 *
 * ── Per-channel registration ──────────────────────────────────────────────────
 *
 * `client-launcher/.../di/Modules.kt` registers two providers, one per outbound
 * routing channel (see `hivens.config.Network` for the full taxonomy):
 *
 *   ◆ default      -- Smartycraft channel: SOCKS-proxied, SSL-bypass-aware.
 *                    Inject for any call to `*.smartycraft.ru`.
 *
 *   ❖ named("direct") -- Direct channel: no proxy, strict TLS.
 *                       Inject for third-party CDNs (GitHub, BellSoft, Maven
 *                       Central). Stays online when the upstream proxy doesn't.
 *
 * The provider returns a *fresh* [current] on every access so the SSL-bypass
 * toggle takes effect on the next request without rebuilding the singleton.
 */
class HttpClientProvider(private val selector: () -> HttpClient) {
    val current: HttpClient get() = selector()
}
