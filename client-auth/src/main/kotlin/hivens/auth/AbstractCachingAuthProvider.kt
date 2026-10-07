package hivens.auth

import hivens.core.api.AuthException
import hivens.core.data.AuthStatus
import hivens.core.data.SessionData
import hivens.core.util.retryWithBackoff
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import io.ktor.client.network.sockets.ConnectTimeoutException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
     * Session cache key: an account, not an account-and-server.
     *
     * The server is deliberately NOT part of it. A live probe against the SC API
     * (2026-09-19, Industrial vs RPG) showed the game token is not scoped to the
     * server it was minted for: the join gate carries no world name, so a session
     * earned for one 1.12.2 world is accepted for another. Keying the cache by
     * server was the launcher imposing a per-server model the API does not have,
     * and it cost a fresh login (and, for a 2FA account, a fresh code, killing the
     * previous session) on every world switch. The provider still sends the server
     * in the login body and still carries it on the returned session for authlib
     * selection, it just does not split the cache on it.
     *
     * Includes [passwordHash] because otherwise a second login with the WRONG
     * password inside the TTL would succeed via cache, masking credential
     * rotation. The hash (never plaintext) is the one the provider already
     * computes for its request.
     */
    protected data class CacheKey(val username: String, val passwordHash: String)

    private data class CachedSession(val session: SessionData, val expiresAt: Long)

    private val sessionCache = ConcurrentHashMap<CacheKey, CachedSession>()

    /**
     * 30 s: long enough for "open launcher -> click Play", short enough that the
     * backend still considers the session fresh. In-memory only; a process
     * restart re-auths. The window also now covers a launch that follows sign-in
     * across a different world, since the key no longer splits on the server.
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
     * failures that never reached the server, then funnels any non-[AuthException]
     * into an [AuthException]: an SSL-certificate problem carries
     * [AuthException.isSslError] (needs user opt-in, not silent retry); anything else
     * becomes a generic INTERNAL_ERROR. [AuthException]s thrown by [block]
     * (server-side rejections) pass through untouched -- retrying those only locks
     * the user out faster.
     *
     * Only the failures [neverReachedServer] names are retried. An auth round trip
     * changes state on the server: a login mints a session and, for a two-factor
     * account, sends a code; a code check spends the code. A read timeout or a reset
     * mid-response is a request that may well have been processed, and running it
     * again sent a second code that made the first one wrong, or checked a spent code
     * and reported it wrong after the sign-in had gone through. Such a failure is
     * handed to the caller, whose own ladder decides when to try again.
     */
    protected suspend fun <T> withRetry(operation: String, block: suspend () -> T): T =
        try {
            retryWithBackoff(operation = operation, shouldRetry = ::neverReachedServer) { block() }
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
     * True for a failure that leaves no doubt the request was never sent: the
     * connection was refused or never established, or the host name did not
     * resolve. NOT true for [AuthException] (server rejections) or SSL cert errors
     * (those need user opt-in).
     */
    internal fun neverReachedServer(t: Throwable): Boolean {
        if (t is AuthException) return false
        if (t.isSslCertificateError()) return false
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is ConnectException ||
                cause is UnknownHostException ||
                cause is NoRouteToHostException ||
                cause is ConnectTimeoutException ||
                // OkHttp reports a connect timeout as a plain SocketTimeoutException;
                // its message is what tells it from a read that timed out.
                (cause is SocketTimeoutException && cause.message?.contains("connect", ignoreCase = true) == true)
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
