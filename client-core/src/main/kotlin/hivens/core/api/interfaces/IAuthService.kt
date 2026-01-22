package hivens.core.api.interfaces

import hivens.core.api.AuthException
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
     * @throws AuthException in case of authentication errors (incorrect password, ban, etc.).
     * @throws IOException in case of network errors (I/O, timeouts, DNS).
     */
    @Throws(AuthException::class, IOException::class)
    suspend fun login(username: String, password: String, serverId: String): SessionData
}
