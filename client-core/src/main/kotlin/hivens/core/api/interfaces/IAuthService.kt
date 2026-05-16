package hivens.core.api.interfaces

import hivens.core.api.AuthException
import hivens.core.api.TwoFactorRequiredException
import hivens.core.data.SessionData
import java.io.IOException

/**
 * Contract for authentication service.
 * Responsible for converting credentials into session data.
 */
interface IAuthService {

    /**
     * Performs authentication on the server.
     *
     * @param username User login.
     * @param password User password.
     * @param serverId The ID of the selected server (as defined by the API).
     * @return A SessionData object containing the token, UUID, and client data.
     * @throws TwoFactorRequiredException when the account has TOTP 2FA -- caller
     *         must prompt for the code and call [completeTwoFactor] to finish.
     * @throws AuthException for other authentication errors (incorrect password, ban, etc.).
     * @throws IOException in case of network errors (I/O, timeouts, DNS).
     */
    suspend fun login(username: String, password: String, serverId: String): SessionData

    /**
     * Completes the 2FA flow started by [login] when it threw
     * [TwoFactorRequiredException]. Sends the user-provided 6-digit code to
     * the server, then re-runs the full login on success -- the second login
     * is what produces a valid [SessionData] (the first one was rejected at
     * the TWOAUTH gate).
     *
     * @param uid `uid` from the TWOAUTH login response, threaded through
     *        [TwoFactorRequiredException.uid]. Required to sign the
     *        twoauth request; pass through verbatim.
     * @param code 6-digit code from the user's authenticator app.
     * @throws AuthException with [hivens.core.data.AuthStatus.WRONG_CODE]
     *         if the code is wrong (UI should re-prompt, max 3 attempts).
     * @throws AuthException with [hivens.core.data.AuthStatus.TWO_FACTOR_EXPIRED]
     *         if the TWOAUTH session has expired server-side -- UI must
     *         restart the full login.
     * @throws AuthException for other errors (network, server-side).
     */
    suspend fun completeTwoFactor(
        username: String,
        password: String,
        serverId: String,
        uid: String,
        code: String,
    ): SessionData
}
