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
 * - **Two-factor account.** Go with the stored session and make no request at
 *   all: a login mints a new uid and invalidates the previous one, so asking
 *   would revoke the session the player unlocked with a code. Checked before
 *   the password, since the token is what this branch trusts.
 * - **Cached password present.** Attempt a real login. On
 *   [TwoFactorRequiredException], trust the cached accessToken in `saved` and
 *   return it marked, so the caller can arm the guard above. On
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

        // A known two-factor account is never signed in from here. The damage is
        // the REQUEST, not its refusal: SmartyCraft mints a uid per login and
        // invalidates the previous one, so this call revokes the session the
        // player unlocked with a code, and a game already running is dropped with
        // a username verification error moments after the launcher window opens.
        // Nothing on screen connects the two. AutoSyncService gates the same call
        // for the same reason.
        //
        // Ahead of the cached-password read, because the token is what this branch
        // goes with and an account that never saved a password still has one.
        if (saved.twoFactor) {
            ActionRing.record("Auto-login: two-factor account, going with the session in hand")
            return Resolution.Success(saved.copy(serverId = lastServerId))
        }

        val cachedPass = saved.cachedPassword ?: return Resolution.NoCredentials
        val server = lastServerId ?: Protocol.DEFAULT_SERVER_ID

        return try {
            Resolution.Success(authService.login(saved.playerName, cachedPass, server))
        } catch (e: TwoFactorRequiredException) {
            // First contact with the gate: the flag is set by whoever meets it, and
            // an account restored from a build that predates the flag meets it here.
            // The demand itself is the evidence, so the session comes back marked and
            // the caller persists it -- otherwise the guard above stays unarmed and
            // every launch spends another login.
            //
            // The cached accessToken is what the shell opens on. A 2FA account already
            // paid for it, and a launch demands a session minted for that launch
            // regardless, so a stale one costs a code at launch rather than a sign-in
            // on every start.
            ActionRing.record(
                "Auto-login: 2FA account, trusting cached accessToken (uid=${e.uid?.take(8) ?: "<missing>"})"
            )
            Resolution.Success(saved.copy(serverId = lastServerId, twoFactor = true))
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
