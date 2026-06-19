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
import hivens.launcher.network.NetworkState
import hivens.launcher.network.ServerProtocolConfig
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

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
 *   [AuthException] with `isSslError`, auto-grant the SSL bypass for 30
 *   days (the user implicitly consented by previously saving credentials
 *   through a cert outage) and retry through the SSL-bypass auth service.
 *   On any other failure, return null and let the user re-enter manually.
 * - **No cached password.** Return null.
 *
 * Returns the [SessionData] on success, `null` on unauthenticated. The
 * caller is responsible for translating that into its own UI state
 * machine; the Compose-side `Loading` placeholder applies while this
 * coroutine is still running.
 *
 * Pure suspend fun, no Compose state, no UI types -- the auto-login
 * decision is business logic and shouldn't live inside a Composable
 * (where it previously sat as a 78-line LaunchedEffect in AppRoot).
 */
object AutoLoginCoordinator {

    private val log = LoggerFactory.getLogger(AutoLoginCoordinator::class.java)

    suspend fun resolveSession(
        settings: SettingsData,
        saved: SessionData?,
        lastServerId: String?,
        authService: AuthProvider,
        insecureAuthService: AuthProvider,
        protocolConfig: ServerProtocolConfig,
        msaProvider: RefreshableAuthProvider? = null,
    ): SessionData? {
        if (settings.isOfflineMode) {
            // Offline identity: the chosen offline name, else the last signed-in
            // name. Real vanilla offline UUID + blank token (so it never persists
            // as a real session); matches OfflineAuthProvider's output.
            val name = settings.offlinePlayerName?.takeIf { it.isNotBlank() }
                ?: saved?.playerName?.takeIf { it.isNotBlank() }
                ?: return null
            return SessionData(
                status      = AuthStatus.OK,
                playerName  = name,
                uuid        = OfflineIdentity.dashlessUuidFor(name),
                accessToken = "",
                offline     = true,
                serverId    = lastServerId,
            )
        }

        if (saved == null) return null

        // Microsoft account: silent-refresh the stored token, falling back to the
        // cached Minecraft token on any failure (or no configured client id).
        // Only Microsoft sessions carry a refresh token, so this never shadows SC.
        saved.refreshToken?.let { refreshToken ->
            val refreshed = runCatching { msaProvider?.refresh(refreshToken) }
                .getOrElse {
                    log.warn("MSA silent refresh failed -- trusting the cached Microsoft token", it)
                    null
                }
            return (refreshed ?: saved).copy(serverId = lastServerId)
        }

        val cachedPass = saved.cachedPassword ?: return null
        val server = lastServerId ?: Protocol.DEFAULT_SERVER_ID

        return try {
            authService.login(saved.playerName, cachedPass, server)
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
            saved.copy(serverId = lastServerId)
        } catch (e: AuthException) {
            if (e.isSslError) {
                recoverWithSslBypass(
                    saved = saved,
                    cachedPass = cachedPass,
                    server = server,
                    insecureAuthService = insecureAuthService,
                    protocolConfig = protocolConfig,
                )
            } else {
                log.warn("Cached-credential auto-login failed (non-SSL)", e)
                null
            }
        } catch (e: Exception) {
            log.warn("Cached-credential auto-login failed with non-Auth exception", e)
            null
        }
    }

    /**
     * Auto-grant on cached-credential cert error gets the same 30-day
     * expiry as user-initiated accept (RightPanel). The user accepted
     * the SSL bypass implicitly by saving credentials through a prior
     * cert outage; we extend that consent until the cert issue resolves
     * or 30 days, whichever comes first.
     */
    private suspend fun recoverWithSslBypass(
        saved: SessionData,
        cachedPass: String,
        server: String,
        insecureAuthService: AuthProvider,
        protocolConfig: ServerProtocolConfig,
    ): SessionData? {
        val until = Instant.now().plus(30, ChronoUnit.DAYS)
        ActionRing.record("SSL bypass auto-granted on cached-credential auto-login (cert error) -- 30 days")
        NetworkState.grantBypass(protocolConfig.sslBypassHost, until)
        return try {
            insecureAuthService.login(saved.playerName, cachedPass, server)
        } catch (e: Exception) {
            log.warn("Auto-login with cached credentials failed after SSL bypass", e)
            null
        }
    }
}
