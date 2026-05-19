package hivens.launcher.network

import hivens.core.diag.ActionRing
import io.ktor.client.HttpClient
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Routes smartycraft network calls through the right channel with
 * automatic IOException-fallback to the SOCKS proxy.
 *
 * Default mode (forceProxyMode = false; typical user):
 * 1. Try via [direct] (no proxy; normal HTTPS to www.smartycraft.ru)
 * 2. On any [IOException] (or wrapping cause), retry via [proxy]
 * 3. If proxy also fails, propagate the original direct exception
 *
 * Force-proxy mode (user opt-in via Settings → Network):
 * - Skip direct entirely; first and only attempt is via [proxy]
 *
 * The official launcher uses direct-as-default with proxy as
 * IOException fallback (smrt-deco's retry chain in `aq.java`); we
 * mirror that, simplified -- the official's no-SSL middle state is
 * unnecessary because direct HTTPS works fine on the smartycraft
 * endpoint. Each fallback hop is recorded into [ActionRing] so the
 * diagnostic bundle reflects "this user's network forced proxy use".
 *
 * Out of scope:
 * - Per-host channel selection (smartycraft is the only host using
 *   this router; GitHub / BellSoft / Maven use a separate `direct`
 *   HttpClient).
 * - Adaptive learning (remember "last N requests via proxy succeeded").
 *   Always tries direct first since network conditions change between
 *   sessions.
 * - SSL-bypass routing -- handled at the OkHttpClient level via
 *   `NetworkState.bypassFor(host)`.
 */
class ChannelRouter(
    private val direct: HttpClient,
    private val proxy: HttpClient,
) {
    private val log = LoggerFactory.getLogger(ChannelRouter::class.java)

    /**
     * Execute [call] through the appropriate channel with fallback.
     * The lambda receives an [HttpClient] and performs HTTP work; the
     * router decides which client to pass on the first call and
     * whether / how to retry.
     */
    suspend fun <T> execute(call: suspend (HttpClient) -> T): T {
        if (NetworkState.forceProxyMode()) {
            log.debug("Force-proxy mode is on -- skipping direct attempt")
            return call(proxy)
        }

        return try {
            call(direct)
        } catch (direct_e: Exception) {
            if (!isFallbackable(direct_e)) throw direct_e
            log.info(
                "Direct channel failed ({}: {}) -- retrying via proxy",
                direct_e.javaClass.simpleName, direct_e.message,
            )
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
     * True if [t] looks like a transient network failure worth
     * retrying via the proxy. HTTP 4xx surface as response status, not
     * exception, so they don't reach here -- this is the defensive
     * guard for non-HTTP exceptions only.
     */
    private fun isFallbackable(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            // SocketException / SocketTimeoutException / ConnectException
            // and ClosedByteChannelException all descend from IOException.
            // The single `is IOException` check is sufficient; the comment
            // names the concrete cases for the reader.
            if (cause is IOException) return true
            cause = cause.cause
        }
        return false
    }
}
