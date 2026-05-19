package hivens.core.api.interfaces

import hivens.core.api.protocol.LoaderResponse
import hivens.core.api.protocol.LoginRequest
import hivens.core.api.protocol.LoginResponse
import hivens.core.api.protocol.StatusOnlyResponse

/**
 * Wire-protocol abstraction over a SmartyCraft-compatible launcher
 * backend. Encapsulates all communication with the upstream
 * `*.smartycraft.ru` endpoints; repositories
 * ([hivens.core.api.AuthService], [hivens.core.api.ServerRepository],
 * etc.) consume this interface and no longer know URL paths, `action=`
 * strings, or signature schemes.
 *
 * Two implementations planned:
 * - `SmartycraftV1Protocol` (in `client-launcher`) speaks the legacy
 *   PHP POST protocol at `/launcher2/index.php`; default for the
 *   foreseeable future.
 * - `MirrorRestProtocol` lands with Project Mirror -- modern REST/JSON
 *   over `/api/v1/...`.
 *
 * Wire details for the V1 implementation: `docs/dev/smartycraft-v1-protocol.md`.
 *
 * ## Contract
 *
 * - All methods MAY throw `IOException` on network failure / server
 *   unreachable. Retry through the fallback channel is the caller's job;
 *   this interface does not retry on its own.
 * - All methods MAY throw `kotlinx.serialization.SerializationException`
 *   when the server returns malformed JSON.
 * - Response objects carry the raw `status` string AND a parsed
 *   [hivens.core.api.protocol.ProtocolStatus]; switch on the enum so
 *   unknown future statuses degrade gracefully to
 *   [hivens.core.api.protocol.ProtocolStatus.ERROR].
 * - Signed actions (spawn, twoauth, uploadSkin, uploadCloak) take `uid`
 *   and `login` as parameters because the signature scheme requires
 *   both; the implementation builds the `check=` MD5 internally.
 */
interface IServerProtocol {

    /**
     * Fetches dashboard -- server list, news, optional-mod manifest
     * per server. Unsigned, unauthenticated (server only checks the
     * launcher version + hash for `UPDATE` gating).
     *
     * On [hivens.core.api.protocol.ProtocolStatus.UPDATE] the impl
     * should have already self-recovered via
     * [hivens.core.api.protocol.LauncherHashCache]; if this method
     * returns UPDATE the cache also failed (no network, or hash refresh
     * exhausted retries) and the caller should surface the error.
     */
    suspend fun loader(): LoaderResponse

    /**
     * Primary auth. Validates credentials, returns the full session +
     * file manifest needed for game launch.
     *
     * On [hivens.core.api.protocol.ProtocolStatus.TWOAUTH] the caller
     * MUST follow up with [twoauth] using the user-provided 6-digit
     * code before treating the session as established.
     */
    suspend fun login(request: LoginRequest): LoginResponse

    /**
     * Resets the player's spawn point on the named server. Distinct
     * from "start game session" -- the in-game session is established
     * by passing [LoginResponse.session] to the child JVM as
     * `--accessToken`; there is no separate server call for that.
     */
    suspend fun spawn(uid: String, login: String, server: String): StatusOnlyResponse

    /** Verifies TOTP 2FA code (sent after [login] returned `TWOAUTH`). */
    suspend fun twoauth(uid: String, login: String, code: String): StatusOnlyResponse

    /**
     * Uploads a new player skin. PNG bytes; server validates dimensions
     * (64x32 or 64x64) and the `hd` permission flag. Returns
     * [hivens.core.api.protocol.ProtocolStatus.SIZE] /
     * [hivens.core.api.protocol.ProtocolStatus.TYPE] /
     * [hivens.core.api.protocol.ProtocolStatus.HD] on validation failure.
     */
    suspend fun uploadSkin(uid: String, login: String, png: ByteArray): StatusOnlyResponse

    /** Uploads a new player cape (cloak). Same shape as [uploadSkin]. */
    suspend fun uploadCloak(uid: String, login: String, png: ByteArray): StatusOnlyResponse
}
