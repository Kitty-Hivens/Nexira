package hivens.auth

import hivens.core.data.SessionData

/**
 * A pluggable authentication backend. The launcher binds exactly one as the
 * active provider (today SmartyCraft; a Mojang / first-party provider is the
 * long arc). Provider-agnostic code -- the login panel, auto-login, the
 * session cache base -- talks to this interface and never to a concrete
 * backend.
 *
 * The contract mirrors the old `IAuthService` so consumers move by import
 * alone, with [capabilities] added so the UI can branch on what a provider
 * actually supports (e.g. skip the 2FA prompt path entirely).
 */
interface AuthProvider {

    /** Stable identifier, e.g. `"smartycraft"`. */
    val id: String

    /** Human-facing label for a future provider picker. */
    val displayName: String

    val capabilities: AuthCapabilities

    /**
     * Authenticates against the backend.
     *
     * @throws hivens.core.api.TwoFactorRequiredException when the account has
     *         TOTP 2FA -- caller prompts for the code and calls
     *         [completeTwoFactor]. Only providers with
     *         [AuthCapabilities.supports2FA] raise this.
     * @throws hivens.core.api.AuthException for other auth errors.
     * @throws java.io.IOException on network errors.
     */
    suspend fun login(username: String, password: String, serverId: String): SessionData

    /**
     * Completes the 2FA flow opened by [login]. Never called on a provider
     * whose [AuthCapabilities.supports2FA] is false; such a provider may throw
     * [UnsupportedOperationException].
     */
    suspend fun completeTwoFactor(
        username: String,
        password: String,
        serverId: String,
        uid: String,
        code: String,
    ): SessionData
}

/** What an [AuthProvider] can do, so callers branch on capability not on `id`. */
data class AuthCapabilities(
    /**
     * Whether the provider runs a second-factor flow. SmartyCraft sets this
     * false on purpose: its 2FA logins succeed on the wire but break the
     * game-side session, so the launcher blocks the path rather than handing
     * the user a broken login.
     */
    val supports2FA: Boolean,

    /**
     * Whether the provider uses the OAuth device-code grant (it also implements
     * [DeviceCodeAuthProvider]). The login UI shows a "sign in" device-code path
     * instead of a username/password form when this is true.
     */
    val supportsDeviceCode: Boolean = false,
)
