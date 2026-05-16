package hivens.core.api.interfaces

import hivens.core.api.protocol.LoaderResponse
import hivens.core.api.protocol.LoginRequest
import hivens.core.api.protocol.LoginResponse
import hivens.core.api.protocol.StatusOnlyResponse

/**
 * Wire-protocol abstraction over a SmartyCraft-compatible launcher backend.
 *
 * Encapsulates ALL communication with the upstream `*.smartycraft.ru`
 * endpoints. Repositories ([hivens.core.api.AuthService],
 * [hivens.core.api.ServerRepository], etc.) consume this interface -- they
 * no longer know URL paths, `action=` strings, or signature schemes.
 *
 * Two implementations planned:
 * - `SmartycraftV1Protocol` (in `client-launcher`) -- speaks the legacy
 *   PHP-based POST protocol at `/launcher2/index.php`. Default for the
 *   foreseeable future, current SmartyCraft production.
 * - `MirrorRestProtocol` (later, when [Project Mirror](https://github.com/Kitty-Hivens/Aura-Launcher/issues/172)
 *   produces an open-source server) -- modern REST/JSON over `/api/v1/...`.
 *
 * Wire details for the V1 implementation are documented in
 * `docs/dev/smartycraft-v1-protocol.md`.
 *
 * ## Contract notes
 *
 * - All methods MAY throw `IOException` (network failure, server unreachable).
 *   Callers should retry through fallback channel; the implementation does
 *   NOT do its own retry -- that's [hivens.core.api.HttpClientProvider]'s
 *   eventual job (see Conduit Phase 2 #155).
 * - All methods MAY throw `kotlinx.serialization.SerializationException`
 *   if server returns malformed JSON.
 * - Response objects carry the raw `status` string AND a parsed
 *   [hivens.core.api.protocol.ProtocolStatus] -- callers should switch on
 *   the parsed enum, not the string, so unknown future status values from
 *   server-side updates degrade gracefully to
 *   [hivens.core.api.protocol.ProtocolStatus.ERROR].
 * - Signed actions (spawn, twoauth, uploadSkin, uploadCloak) take `uid` and
 *   `login` as parameters because the signature scheme requires both. The
 *   implementation builds the `check=` MD5 internally; callers don't compute
 *   signatures.
 */
interface IServerProtocol {

    /**
     * Fetch the dashboard -- server list, news, optional-mod manifest per
     * server. Unsigned, unauthenticated (server only checks the launcher
     * version+hash for `UPDATE` gating).
     *
     * On [hivens.core.api.protocol.ProtocolStatus.UPDATE], the implementation
     * SHOULD have already self-recovered via [hivens.core.api.protocol.LauncherHashCache];
     * if this method returns UPDATE the cache also failed (network or hash
     * couldn't refresh in 2 retries) and caller should surface the error.
     */
    suspend fun loader(): LoaderResponse

    /**
     * Primary auth. Validates credentials, returns full session + file
     * manifest needed for game launch.
     *
     * On status [hivens.core.api.protocol.ProtocolStatus.TWOAUTH], caller
     * MUST follow up with [twoauth] using the user-provided 6-digit code
     * before treating the session as established.
     */
    suspend fun login(request: LoginRequest): LoginResponse

    /**
     * Reset player's spawn point on the named server. Distinct from "start
     * game session" -- the in-game session is established by passing
     * [LoginResponse.session] to the child JVM as `--accessToken`, no
     * separate server call.
     *
     * @param uid 128-char hex from [LoginResponse.uid].
     * @param login Username (NOT email).
     * @param server Server name as returned in [LoaderResponse.servers].
     */
    suspend fun spawn(uid: String, login: String, server: String): StatusOnlyResponse

    /**
     * Verify TOTP 2FA code (sent after [login] returned status TWOAUTH).
     *
     * @param code Six-digit string from user's authenticator app.
     */
    suspend fun twoauth(uid: String, login: String, code: String): StatusOnlyResponse

    /**
     * Upload a new player skin. PNG bytes; server validates dimensions
     * (64x32 or 64x64) and `hd` permission flag. Returns
     * [hivens.core.api.protocol.ProtocolStatus.SIZE]/[hivens.core.api.protocol.ProtocolStatus.TYPE]/
     * [hivens.core.api.protocol.ProtocolStatus.HD] on validation failure.
     */
    suspend fun uploadSkin(uid: String, login: String, png: ByteArray): StatusOnlyResponse

    /**
     * Upload a new player cape (cloak). Same shape as [uploadSkin].
     */
    suspend fun uploadCloak(uid: String, login: String, png: ByteArray): StatusOnlyResponse
}
