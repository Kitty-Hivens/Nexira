package hivens.auth

import hivens.core.data.AuthStatus
import hivens.core.data.OfflineIdentity
import hivens.core.data.SessionData

/**
 * Offline-play identity provider: mints a local [SessionData] from a chosen name
 * with no network and no credentials. The session carries the vanilla offline
 * UUID (see [OfflineIdentity]) and a BLANK accessToken -- blank so the credential
 * store (which keys "auth completed" off a non-blank token) never persists it,
 * and so the launch args degrade it to "0". The launch path emits
 * `--userType legacy` for an offline session.
 *
 * A direct [AuthProvider] implementation, NOT the caching base: there is no
 * password, no network, and nothing to retry.
 */
class OfflineAuthProvider : AuthProvider {

    override val id: String = PROVIDER_KEY
    override val displayName: String = "Offline"
    override val capabilities: AuthCapabilities = AuthCapabilities(supports2FA = false)

    override suspend fun login(username: String, password: String, serverId: String): SessionData {
        val name = username.trim()
        require(name.isNotEmpty()) { "offline player name must not be blank" }
        return SessionData(
            status = AuthStatus.OK,
            playerName = name,
            uuid = OfflineIdentity.dashlessUuidFor(name),
            accessToken = "",
            offline = true,
        )
    }

    override suspend fun completeTwoFactor(
        username: String,
        password: String,
        serverId: String,
        uid: String,
        code: String,
    ): SessionData = throw UnsupportedOperationException("offline auth has no second factor")

    companion object {
        const val PROVIDER_KEY: String = "offline"
    }
}
