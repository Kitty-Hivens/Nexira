package hivens.core.api

import io.ktor.client.HttpClient

/**
 * Delegates to the appropriate [HttpClient] on every request. [selector]
 * returns a fresh client per access so the SSL-bypass toggle takes effect
 * on the next request without rebuilding the singleton.
 *
 * Two providers registered in `client-launcher/.../di/Modules.kt`:
 *   - default          -- Smartycraft channel, SSL-bypass-aware
 *   - named("direct")  -- everything else (GitHub, BellSoft, Maven Central, the mirror; strict TLS)
 */
class HttpClientProvider(private val selector: () -> HttpClient) {
    val current: HttpClient get() = selector()
}
