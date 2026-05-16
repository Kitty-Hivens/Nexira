package hivens.core.api

import hivens.core.data.AuthStatus

/**
 * The exception thrown when authentication to the server fails.
 */
open class AuthException(
    val status: AuthStatus,
    message: String,
    /** True when failure is caused by an expired/invalid SSL certificate. */
    val isSslError: Boolean = false
) : Exception(message)

/**
 * Thrown by [hivens.core.api.interfaces.IAuthService.login] when the server
 * returns `TWOAUTH` -- the account has TOTP 2FA configured. The UI catches
 * this, prompts for the 6-digit code, and follows up with
 * [hivens.core.api.interfaces.IAuthService.completeTwoFactor]. Carries the
 * `uid` the server issued in the TWOAUTH response (required to sign the
 * twoauth follow-up); when null, the server stripped enough of the response
 * that we can't continue and the user must restart the login (#159).
 */
class TwoFactorRequiredException(
    val uid: String?,
    val login: String,
) : AuthException(AuthStatus.NEED_2FA, "2FA required for $login")
