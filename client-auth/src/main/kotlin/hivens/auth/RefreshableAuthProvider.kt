package hivens.auth

import hivens.core.data.SessionData

/**
 * An auth backend that can silently re-mint a session from a stored refresh
 * token, with no user interaction. Auto-login calls this on startup to keep a
 * long-lived account (e.g. Microsoft) signed in without re-prompting.
 *
 * A provider implements this alongside [AuthProvider]; callers resolve it from
 * the registry by type ([AuthProviderRegistry.all] + `filterIsInstance`), never
 * by provider id.
 */
interface RefreshableAuthProvider {

    /**
     * Re-mint a session from [refreshToken]. The returned [SessionData] carries
     * a fresh access token and, when the backend rotates it, a new refresh token
     * the caller should persist. Throws on an expired/revoked token or backend
     * failure -- the caller falls back to the cached token.
     */
    suspend fun refresh(refreshToken: String): SessionData
}
