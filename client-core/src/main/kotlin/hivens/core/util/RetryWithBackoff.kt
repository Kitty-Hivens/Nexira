package hivens.core.util

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("RetryWithBackoff")

/**
 * Retries [block] up to [attempts] times with a hard-coded 1s / 3s / 9s
 * backoff between attempts, retrying only on exceptions matching
 * [shouldRetry]. The last exception bubbles up unmodified.
 *
 * Designed narrowly for the "transient HTTP/2 reset over SOCKS" case the
 * launcher hits in production — auth flow and chunk downloads on the
 * smartycraft channel periodically die mid-stream and a single retry
 * almost always succeeds. Not a general-purpose retry utility; resist
 * the urge to grow it.
 *
 * The [operation] string is for logs only — appears in the retry warning
 * lines so a user log paste shows which call is flapping.
 */
suspend fun <T> retryWithBackoff(
    operation: String,
    attempts: Int = 3,
    shouldRetry: (Throwable) -> Boolean,
    block: suspend () -> T,
): T {
    require(attempts >= 1) { "attempts must be >= 1, got $attempts" }

    val backoffMs = longArrayOf(1_000, 3_000, 9_000)
    var lastError: Throwable? = null

    repeat(attempts) { i ->
        try {
            return block()
        } catch (e: Throwable) {
            if (!shouldRetry(e)) throw e
            lastError = e
            if (i < attempts - 1) {
                val wait = backoffMs.getOrElse(i) { backoffMs.last() }
                log.warn(
                    "{} attempt {}/{} failed: {} — retrying in {}ms",
                    operation, i + 1, attempts, e.message ?: e::class.simpleName, wait,
                )
                delay(wait)
            } else {
                log.error(
                    "{} failed after {} attempts: {}",
                    operation, attempts, e.message ?: e::class.simpleName,
                )
            }
        }
    }
    throw lastError!!
}
