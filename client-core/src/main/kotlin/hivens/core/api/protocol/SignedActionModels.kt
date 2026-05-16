package hivens.core.api.protocol

import kotlinx.serialization.Serializable

/**
 * Generic minimal response for signed actions that don't return data --
 * just a status. Used by `spawn`, `twoauth`, `skinupload`, `cloakupload`.
 */
@Serializable
data class StatusOnlyResponse(
    val status: String,
    val message: String? = null,
) {
    val parsedStatus: ProtocolStatus get() = ProtocolStatus.fromWire(status)
}

/**
 * Request body for `action=spawn` (NOT `tospawn` -- the upstream server has both;
 * `spawn` is "reset player's spawn point in-game", `tospawn` is "start game
 * session". Aura uses `spawn` only -- game session is established by passing
 * the access token from [LoginResponse.session] to the child JVM directly).
 *
 * Signed: signature = `md5(time/10 | uid | login | server)` passed as
 * `check=` URL parameter. See [SmartycraftSignatureBuilder].
 *
 * Currently, 1.12.2-only per gameplay convention.
 */
@Serializable
data class SpawnRequest(
    val login: String,
    val server: String,
)

/**
 * Request body for `action=twoauth` (TOTP 2FA verification follow-up).
 *
 * Signed: signature = `md5(time/10 | uid | login | code)` passed as `check=`.
 *
 * [code] is the 6-digit string the user types from their authenticator app.
 * Server validates against the secret it has on file for this account.
 */
@Serializable
data class TwoAuthRequest(
    val login: String,
    val code: String,
)

/**
 * Request body for `action=skinupload` and `action=cloakupload`. The actual
 * binary payload (PNG bytes) is sent as a separate multipart part named
 * "skin" or "cloak" -- see protocol impl.
 *
 * Signed: signature = `md5(time/10 | uid | login)` (no extra context fields).
 */
@Serializable
data class UploadRequest(
    val login: String,
)
