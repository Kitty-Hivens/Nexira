package hivens.core.api.protocol

import kotlinx.serialization.Serializable

/** Generic minimal response for signed actions that don't return data (twoauth, skinupload, cloakupload). */
@Serializable
data class StatusOnlyResponse(
    val status: String,
    val message: String? = null,
) {
    val parsedStatus: ProtocolStatus get() = ProtocolStatus.fromWire(status)
}

/**
 * Request body for `action=twoauth` (TOTP 2FA verification follow-up).
 * Signed: `check = md5(time/10 | uid | login | code)`. [code] is the
 * 6-digit string the user types from their authenticator app; the
 * server validates against the secret it has on file.
 */
@Serializable
data class TwoAuthRequest(
    val login: String,
    val code: String,
)

/**
 * Request body for `action=skinupload` / `action=cloakupload`. The PNG
 * bytes are sent as a separate multipart part named "skin" or "cloak"
 * (see protocol impl). Signed: `check = md5(time/10 | uid | login)`.
 */
@Serializable
data class UploadRequest(
    val login: String,
)
