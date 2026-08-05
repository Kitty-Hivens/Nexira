package hivens.auth

import hivens.core.api.AuthException
import hivens.core.data.AuthStatus
import hivens.core.data.SessionData
import hivens.core.util.retryWithBackoff
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Base for [AuthProvider]s that dedupe rapid re-logins with a short-lived
 * session cache and run each backend round-trip through a uniform retry +
 * error-translation funnel. Everything here is provider-agnostic: it knows
 * nothing about any backend's wire format, token scheme, or 2FA semantics --
 * the concrete provider owns those and calls [cachedSession] / [cacheSession]
 * / [withRetry] from its own login flow.
 */
abstract class AbstractCachingAuthProvider : AuthProvider {

    protected val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Per-server session cache key. Includes [passwordHash] because otherwise
     * a second login with the WRONG password inside the TTL would succeed via
     * cache, masking credential rotation. The hash (never plaintext) is the
     * one the provider already computes for its request.
     */
    protected data class CacheKey(val username: String, val passwordHash: String, val serverId: String)

    private data class CachedSession(val session: SessionData, val expiresAt: Long)

    private val sessionCache = ConcurrentHashMap<CacheKey, CachedSession>()

    /**
     * 30 s: long enough for "open launcher -> pick server -> click Play" (which
     * historically did two consecutive logins for the same server), short
     * enough that the backend still considers the session fresh. In-memory
     * only; a process restart re-auths.
     */
    private val sessionTtlMs = 30_000L

    /** Cached session for [key], or null when absent or expired (lazily evicted). */
    protected fun cachedSession(key: CacheKey): SessionData? {
        val cached = sessionCache[key] ?: return null
        if (System.currentTimeMillis() >= cached.expiresAt) {
            sessionCache.remove(key, cached)
            return null
        }
        return cached.session
    }

    protected fun cacheSession(key: CacheKey, session: SessionData) {
        sessionCache[key] = CachedSession(session, System.currentTimeMillis() + sessionTtlMs)
    }

    /**
     * Runs a single backend round-trip ([block]) through retry-with-backoff for
     * transient failures, then funnels any non-[AuthException] into an
     * [AuthException]: an SSL-certificate problem carries [AuthException.isSslError]
     * (needs user opt-in, not silent retry); anything else becomes a generic
     * INTERNAL_ERROR. [AuthException]s thrown by [block] (server-side rejections)
     * pass through untouched -- retrying those only locks the user out faster.
     */
    protected suspend fun <T> withRetry(operation: String, block: suspend () -> T): T =
        try {
            retryWithBackoff(operation = operation, shouldRetry = ::isTransientNetworkError) { block() }
        } catch (e: CancellationException) {
            // On the JVM this is an ordinary Exception, so the funnel below would
            // swallow it and hand the caller a Network Error for a login the user
            // simply cancelled -- closing the dialog, or navigating away while the
            // request is in flight. AutoLoginCoordinator reads that as the server
            // being unreachable and enters its retry ladder, and the parent job
            // never learns it was cancelled at all.
            throw e
        } catch (e: Exception) {
            if (e is AuthException) throw e
            logger.error("{} error", operation, e)
            if (e.isSslCertificateError()) {
                throw AuthException(
                    status = AuthStatus.INTERNAL_ERROR,
                    message = "SSL certificate error: ${e.message}",
                    isSslError = true,
                )
            }
            throw AuthException(AuthStatus.INTERNAL_ERROR, "Network Error: ${e.message}", isNetworkError = true)
        }

    /**
     * True for the narrow set of transient network failures seen on the auth
     * channel -- h2 frame resets over SOCKS, raw socket resets during TLS,
     * ktor's wrapped channel-closed exception. NOT true for [AuthException]
     * (server rejections) or SSL cert errors (those need user opt-in).
     */
    private fun isTransientNetworkError(t: Throwable): Boolean {
        if (t is AuthException) return false
        if (t.isSslCertificateError()) return false
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is java.net.ConnectException ||
                cause is java.net.SocketException ||
                cause is io.ktor.utils.io.ClosedByteChannelException ||
                cause is java.net.SocketTimeoutException
            ) return true
            if (cause is java.io.IOException &&
                cause.message?.contains("Connection reset", ignoreCase = true) == true
            ) return true
            cause = cause.cause
        }
        return false
    }

    private fun Throwable.isSslCertificateError(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is javax.net.ssl.SSLHandshakeException ||
                cause is java.security.cert.CertPathValidatorException ||
                cause.message?.contains("certificate_expired") == true ||
                cause.message?.contains("CertPathValidatorException") == true
            ) return true
            cause = cause.cause
        }
        return false
    }
}
