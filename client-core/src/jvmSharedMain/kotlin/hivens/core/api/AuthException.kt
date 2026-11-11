package hivens.core.api

import hivens.core.data.AuthStatus

open class AuthException(
    val status: AuthStatus,
    message: String,
    /** True when caused by an expired or invalid SSL certificate. */
    val isSslError: Boolean = false,
    /**
     * True when the failure is network-shaped (DNS, connect, reset) -- nothing
     * reached the server, so the credentials were never judged and a retry
     * once connectivity returns is safe. False for server-side rejections.
     */
    val isNetworkError: Boolean = false,
) : Exception(message)

/**
 * Thrown when the server returns `TWOAUTH` (account has TOTP 2FA). UI
 * catches this, prompts for the 6-digit code, follows up via the auth
 * provider's `completeTwoFactor`. [uid] is the session identifier from the
 * TWOAUTH response (required to sign the follow-up); a null [uid] means the
 * server response was truncated and the user must restart login.
 */
class TwoFactorRequiredException(
    val uid: String?,
    val login: String,
) : AuthException(AuthStatus.NEED_2FA, "2FA required for $login")
