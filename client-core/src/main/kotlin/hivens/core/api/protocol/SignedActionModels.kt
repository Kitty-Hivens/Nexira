package hivens.core.api.protocol

import kotlinx.serialization.Serializable

/** Generic minimal response for signed actions that don't return data (spawn, twoauth, skinupload, cloakupload). */
@Serializable
data class StatusOnlyResponse(
    val status: String,
    val message: String? = null,
) {
    val parsedStatus: ProtocolStatus get() = ProtocolStatus.fromWire(status)
}

/**
 * Request body for `action=spawn` (NOT `tospawn` -- upstream has both;
 * `spawn` resets the player's spawn point in-game, `tospawn` starts a
 * game session). Aura uses `spawn` only; sessions are established by
 * passing the access token to the child JVM directly.
 *
 * Signed: `check = md5(time/10 | uid | login | server)`. 1.12.2-only.
 */
@Serializable
data class SpawnRequest(
    val login: String,
    val server: String,
)

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
