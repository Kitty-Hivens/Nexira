package hivens.core.api.interfaces

import hivens.core.api.AuthException
import hivens.core.api.TwoFactorRequiredException
import hivens.core.data.SessionData
import java.io.IOException

interface IAuthService {
    /**
     * Authenticates against the server.
     *
     * @throws TwoFactorRequiredException when the account has TOTP 2FA --
     *         caller prompts for the code and calls [completeTwoFactor].
     * @throws AuthException for other authentication errors (wrong password, ban).
     * @throws IOException on network errors.
     */
    suspend fun login(username: String, password: String, serverId: String): SessionData

    /**
     * Completes the 2FA flow opened by [login] when it threw
     * [TwoFactorRequiredException]. Sends the user's 6-digit code, then
     * re-runs the full login -- that second login is what produces the
     * valid [SessionData] (the first was rejected at the TWOAUTH gate).
     *
     * [uid] threads through from [TwoFactorRequiredException.uid] and
     * signs the twoauth request; pass verbatim.
     *
     * @throws AuthException with [hivens.core.data.AuthStatus.WRONG_CODE]
     *         when the code is wrong; UI re-prompts (max 3 attempts).
     * @throws AuthException with [hivens.core.data.AuthStatus.TWO_FACTOR_EXPIRED]
     *         when the TWOAUTH session expired server-side; UI must
     *         restart the full login.
     */
    suspend fun completeTwoFactor(
        username: String,
        password: String,
        serverId: String,
        uid: String,
        code: String,
    ): SessionData
}
