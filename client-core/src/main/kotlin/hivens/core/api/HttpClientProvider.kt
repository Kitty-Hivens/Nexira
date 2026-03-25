package hivens.core.api

import io.ktor.client.HttpClient

/**
 * Delegates to the appropriate [HttpClient] on every request.
 * The selection logic is provided by the caller via [selector] lambda,
 * keeping this class free of any launcher-specific dependencies.
 */
class HttpClientProvider(private val selector: () -> HttpClient) {
    val current: HttpClient get() = selector()
}
