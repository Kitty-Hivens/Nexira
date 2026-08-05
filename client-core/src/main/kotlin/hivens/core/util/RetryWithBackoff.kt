package hivens.core.util

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("RetryWithBackoff")

/**
 * Retries [block] up to [attempts] times, waiting 1s / 3s / 9s between
 * them, and only when [shouldRetry] matches. A cancellation is never a
 * retry and never reaches [shouldRetry]. Last exception bubbles up
 * unmodified.
 *
 * The wait runs between attempts, not after the last one, so the default
 * `attempts = 3` spends two of the three delays.
 *
 * Designed narrowly for transfers that die mid-stream on a flaky route
 * (auth calls and file downloads both do; one retry almost always
 * succeeds). Not a general-purpose retry utility -- resist growing it.
 *
 * [operation] is for logs only; appears in retry warnings so a user
 * log paste shows which call is flapping.
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
        } catch (e: CancellationException) {
            // Never consult [shouldRetry] for a cancellation: a predicate written
            // for network shapes could match one by accident, and re-running the
            // block would resurrect work the caller already stopped -- for an auth
            // call, a second login that invalidates the session the first won.
            throw e
        } catch (e: Throwable) {
            if (!shouldRetry(e)) throw e
            lastError = e
            if (i < attempts - 1) {
                val wait = backoffMs.getOrElse(i) { backoffMs.last() }
                log.warn(
                    "{} attempt {}/{} failed: {} -- retrying in {}ms",
                    operation, i + 1, attempts, e.message ?: e::class.simpleName, wait,
                )
                delay(wait.milliseconds)
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
