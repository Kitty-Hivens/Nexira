package hivens.launcher.bootstrap

import hivens.config.Protocol
import hivens.core.api.AuthException
import hivens.core.api.TwoFactorRequiredException
import hivens.auth.AuthProvider
import hivens.auth.RefreshableAuthProvider
import hivens.core.data.AuthStatus
import hivens.core.data.OfflineIdentity
import hivens.core.data.SessionData
import hivens.core.data.SettingsData
import hivens.core.diag.ActionRing
import org.slf4j.LoggerFactory

/**
 * Resolves the launcher's initial auth state from cached credentials.
 *
 * Three input cases the coordinator unifies:
 *
 * - **Offline mode is on.** Synthesize an offline-identity session (vanilla
 *   offline UUID, blank token) from the chosen offline name, else the last
 *   signed-in name; null when neither exists. No network call.
 * - **Microsoft account active.** The session carries a refresh token (SC
 *   sessions never do). Silent-refresh it for a fresh Minecraft token; on any
 *   failure (or no configured client id) trust the cached token -- the MC
 *   token lives ~24h, and a stale one is rejected at launch, the same recovery
 *   as a stale SC token.
 * - **Cached password present.** Attempt a real login. On
 *   [TwoFactorRequiredException], trust the cached accessToken in `saved`
 *   (2FA accounts already paid the 2FA cost when they got that token --
 *   re-validating on every startup defeats the point). On
 *   [AuthException] with `isSslError`, stop at [Resolution.CertificateUntrusted]
 *   and leave the decision to the user.
 *   On any other failure, return null and let the user re-enter manually.
 * - **No cached password.** Return [Resolution.NoCredentials].
 *
 * Returns a [Resolution]: [Resolution.Success] carries the session;
 * [Resolution.NetworkDown] means nothing reached the server (the caller may
 * retry when connectivity returns); [Resolution.Rejected] and
 * [Resolution.NoCredentials] are terminal -- retrying rejected credentials
 * only hammers the upstream, and there is nothing to retry without any.
 * The caller translates the resolution into its own UI state machine; the
 * Compose-side `Loading` placeholder applies while this coroutine runs.
 *
 * Pure suspend fun, no Compose state, no UI types -- the auto-login
 * decision is business logic and shouldn't live inside a Composable
 * (where it previously sat as a 78-line LaunchedEffect in AppRoot).
 */
object AutoLoginCoordinator {

    private val log = LoggerFactory.getLogger(AutoLoginCoordinator::class.java)

    sealed interface Resolution {
        data class Success(val session: SessionData) : Resolution

        /** No saved account, password, or offline name -- nothing to attempt. */
        data object NoCredentials : Resolution

        /** The server answered and said no (or the failure is unclassifiable). */
        data object Rejected : Resolution

        /** Network-shaped failure: the server was never reached. Retryable. */
        data object NetworkDown : Resolution

        /**
         * The host presented a certificate we do not trust. Terminal here on
         * purpose: disabling certificate verification is the user's call, taken
         * at the explicit prompt in the login panel, and an attacker who can
         * present a bad certificate is exactly who benefits from the launcher
         * making that call on its own.
         */
        data object CertificateUntrusted : Resolution
    }

    suspend fun resolveSession(
        settings: SettingsData,
        saved: SessionData?,
        lastServerId: String?,
        authService: AuthProvider,
        msaProvider: RefreshableAuthProvider? = null,
    ): Resolution {
        if (settings.isOfflineMode) {
            // Offline identity: the chosen offline name, else the last signed-in
            // name. Real vanilla offline UUID + blank token (so it never persists
            // as a real session); matches OfflineAuthProvider's output.
            val name = settings.offlinePlayerName?.takeIf { it.isNotBlank() }
                ?: saved?.playerName?.takeIf { it.isNotBlank() }
                ?: return Resolution.NoCredentials
            return Resolution.Success(
                SessionData(
                    status      = AuthStatus.OK,
                    playerName  = name,
                    uuid        = OfflineIdentity.dashlessUuidFor(name),
                    accessToken = "",
                    offline     = true,
                    serverId    = lastServerId,
                ),
            )
        }

        if (saved == null) return Resolution.NoCredentials

        // Microsoft account: silent-refresh the stored token, falling back to the
        // cached Minecraft token on any failure (or no configured client id).
        // Only Microsoft sessions carry a refresh token, so this never shadows SC.
        saved.refreshToken?.let { refreshToken ->
            val refreshed = runCatching { msaProvider?.refresh(refreshToken) }
                .getOrElse {
                    log.warn("MSA silent refresh failed -- trusting the cached Microsoft token", it)
                    null
                }
            return Resolution.Success((refreshed ?: saved).copy(serverId = lastServerId))
        }

        val cachedPass = saved.cachedPassword ?: return Resolution.NoCredentials
        val server = lastServerId ?: Protocol.DEFAULT_SERVER_ID

        return try {
            Resolution.Success(authService.login(saved.playerName, cachedPass, server))
        } catch (e: TwoFactorRequiredException) {
            // 2FA accounts already paid the 2FA cost when they got the
            // cached accessToken. Re-validating with login() just
            // re-triggers the gate on every launcher startup -- which
            // is what the cached accessToken is supposed to prevent.
            // Trust the cache: promote `saved` straight to Authenticated.
            // If the token is actually stale, the server will reject it
            // at game launch and the user re-logs in from the credentials
            // form -- same recovery path as a server-side logout. Fix
            // for the "double login on every launch with 2FA" report.
            ActionRing.record(
                "Auto-login: 2FA account, trusting cached accessToken (uid=${e.uid?.take(8) ?: "<missing>"})"
            )
            Resolution.Success(saved.copy(serverId = lastServerId))
        } catch (e: AuthException) {
            when {
                e.isSslError -> {
                    log.warn("Cached-credential auto-login hit a certificate error -- the bypass is the user's to grant")
                    ActionRing.record("Auto-login stopped: certificate not trusted, awaiting an explicit decision")
                    Resolution.CertificateUntrusted
                }
                e.isNetworkError -> {
                    log.warn("Cached-credential auto-login failed (network down): {}", e.message)
                    Resolution.NetworkDown
                }
                else -> {
                    log.warn("Cached-credential auto-login rejected", e)
                    Resolution.Rejected
                }
            }
        } catch (e: Exception) {
            // Raw I/O that escaped the provider's funnel is still network-shaped;
            // anything else is unclassifiable and treated as terminal.
            if (e is java.io.IOException) {
                log.warn("Cached-credential auto-login failed (raw I/O): {}", e.message)
                Resolution.NetworkDown
            } else {
                log.warn("Cached-credential auto-login failed with non-Auth exception", e)
                Resolution.Rejected
            }
        }
    }

    /**
     * Backoff ladder for [Resolution.NetworkDown] retries: quick first
     * retries for a blip, then a flat five-minute cadence forever -- a
     * launcher left open overnight signs itself in when the network
     * returns without ever hammering the upstream. [attempt] counts
     * completed failures (0-based).
     */
    fun retryDelayMs(attempt: Int): Long {
        val ladder = longArrayOf(15_000, 30_000, 60_000, 120_000)
        return ladder.getOrNull(attempt) ?: 300_000L
    }

}
