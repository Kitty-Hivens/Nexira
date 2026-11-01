package hivens.auth

import hivens.core.data.SessionData

/**
 * A multi-step interactive auth backend using the OAuth 2.0 device authorization
 * grant: [requestDeviceCode] returns a user code + verification URL to display,
 * then [awaitToken] polls until the user approves (or it expires / is declined).
 *
 * Distinct from [AuthProvider.login] -- there is no prior credential; the first
 * call PRODUCES the challenge. A provider implements this alongside [AuthProvider]
 * (so the registry/gate treat it as a provider); callers branch on
 * [AuthCapabilities.supportsDeviceCode], never on the provider id.
 */
interface DeviceCodeAuthProvider {

    /** Begin a device-code sign-in: returns the code/URL to show and the poll token. */
    suspend fun requestDeviceCode(): DeviceCodeChallenge

    /**
     * Poll until sign-in completes, returning the resulting [SessionData].
     * Cancellable (cancel the calling coroutine to abort). Throws
     * [hivens.core.api.AuthException] on expiry, decline, or backend failure.
     */
    suspend fun awaitToken(challenge: DeviceCodeChallenge): SessionData
}

/**
 * A pending device-code sign-in. [userCode] + [verificationUri] are shown to the
 * user; [deviceCode] is the opaque poll token; [intervalSeconds] paces the poll
 * and [expiresInSeconds] bounds it.
 */
data class DeviceCodeChallenge(
    val userCode: String,
    val verificationUri: String,
    val deviceCode: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
)
