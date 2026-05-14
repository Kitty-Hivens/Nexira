package hivens.launcher.network

import hivens.core.diag.ActionRing
import hivens.launcher.NetworkState
import io.ktor.client.HttpClient
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Routes smartycraft network calls through the right channel with automatic
 * IOException-fallback to the SOCKS proxy.
 *
 * ## Behavior
 *
 * Default mode (forceProxyMode=false, the typical user):
 * 1. Try via [direct] (no proxy, normal HTTPS to www.smartycraft.ru)
 * 2. On any [IOException] (or wrapping cause), retry via [proxy]
 * 3. If proxy also fails, propagate the original exception
 *
 * Force-proxy mode (user opt-in via Settings → Network → "Force proxy mode"):
 * - Skip direct entirely; first and only attempt is via [proxy]
 *
 * ## Why this shape
 *
 * Pre-Conduit, every call went through the proxy unconditionally — wasted
 * ~500 ms per request (verified empirically 2026-05-14, see
 * `reference_smartycraft_proxy`). The official launcher uses direct as
 * default with proxy as IOException fallback (smrt-deco's three-state
 * retry chain in `aq.java`). Conduit Phase 2 mirrors that, simplified to
 * two states (we don't need the no-SSL middle state — direct HTTPS works
 * fine on the smartycraft endpoint).
 *
 * Each fallback hop is recorded into [ActionRing] so the diagnostic
 * bundle reflects "this user's network forced proxy use" — useful when
 * triaging support requests from regions where direct doesn't work.
 *
 * ## Out of scope
 *
 * - Per-host channel selection (today smartycraft is the only host using
 *   this router; GitHub/BellSoft/Maven all use a separate `direct`
 *   HttpClient that doesn't go through this).
 * - Adaptive learning (remembering "last 3 requests via proxy succeeded
 *   so try proxy first"). Current shape always tries direct first because
 *   network conditions can change between sessions.
 * - SSL bypass routing — that's NetworkState.bypassFor(host) territory,
 *   handled at the OkHttpClient level.
 */
class ChannelRouter(
    private val direct: HttpClient,
    private val proxy: HttpClient,
) {
    private val log = LoggerFactory.getLogger(ChannelRouter::class.java)

    /**
     * Execute [call] through the appropriate channel with fallback.
     *
     * The lambda receives an [HttpClient] and should perform whatever HTTP
     * work it needs to do, returning the result. The router decides which
     * client to pass on the first call and whether/how to retry.
     */
    suspend fun <T> execute(call: suspend (HttpClient) -> T): T {
        if (NetworkState.forceProxyMode()) {
            log.debug("Force-proxy mode is on — skipping direct attempt")
            return call(proxy)
        }

        return try {
            call(direct)
        } catch (direct_e: Exception) {
            if (!isFallbackable(direct_e)) throw direct_e
            log.info("Direct channel failed ({}: {}) — retrying via proxy",
                direct_e.javaClass.simpleName, direct_e.message)
            ActionRing.record("Direct connection failed (${direct_e.javaClass.simpleName}), retrying via proxy")
            try {
                call(proxy)
            } catch (proxy_e: Exception) {
                log.error("Proxy channel also failed; propagating original direct exception", proxy_e)
                throw direct_e
            }
        }
    }

    /**
     * True if [t] looks like a transient network failure worth retrying via
     * the proxy. Excludes deliberate server-side rejections (HTTP 4xx
     * surface as response status, not exception, so they don't reach here
     * — but defensive guard for non-HTTP exceptions).
     */
    private fun isFallbackable(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            when (cause) {
                is IOException,
                is java.net.SocketException,
                is java.net.SocketTimeoutException,
                is java.net.ConnectException,
                is io.ktor.utils.io.ClosedByteChannelException -> return true
            }
            cause = cause.cause
        }
        return false
    }
}
