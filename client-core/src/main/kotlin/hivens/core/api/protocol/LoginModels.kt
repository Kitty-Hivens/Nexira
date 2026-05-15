package hivens.core.api.protocol

import hivens.core.data.FileManifest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for `action=login`. Unsigned (uses password MD5 in body).
 *
 * All 12 fields are required — server returns HTTP 500 if any is absent
 * (verified empirically 2026-05-14). [classPath] and [rtCheckSum] are
 * cargo-cult — server reads them but doesn't validate content; pass any
 * non-blank string.
 *
 * [password] must be `MD5(plaintext)` lowercase hex. No salt — server stores
 * passwords as plain MD5 (security posture documented in
 * `reference_smartycraft_community.md`).
 *
 * [session] is a client-generated random 32-char hex (UUID minus dashes
 * works fine). Persisted in subsequent server-side response as the AES-
 * encrypted session token.
 */
@Serializable
data class LoginRequest(
    val login: String,
    val password: String,
    val server: String,
    val session: String,
    val mac: String,
    val osName: String,
    val osBitness: Int,
    val javaVersion: String,
    val javaBitness: Int,
    val javaHome: String,
    val classPath: String,
    val rtCheckSum: String,
)

/**
 * Response from `action=login`. Carries the full session needed to launch
 * the game and download files.
 *
 * - [uid]: 128-char hex (SHA-512-shaped). Used as input to all signed-action
 *   signatures. Treat as opaque — never log raw, redact to first 8 chars when
 *   diagnostic context demands.
 * - [session]: Base64 of 32 bytes. AES-encrypted continuation token.
 *   Decrypts via key = first 16 chars of `MD5(uid + AUTH_SALT)`,
 *   `AES/ECB/PKCS5Padding`. We don't decrypt it — pass through as-is to the
 *   game's `--accessToken` argument.
 * - [client]: file manifest the launcher must reconcile to local disk before
 *   game launch. [hivens.launcher.FileDownloadService] consumes this.
 */
@Serializable
data class LoginResponse(
    val status: String,
    val playername: String? = null,
    val uid: String? = null,
    val uuid: String? = null,
    val session: String? = null,
    val money: Int = 0,
    val hd: Int = 0,
    val clan: String? = null,
    val cape: String? = null,
    val skintime: Int = 0,
    val capetime: Int? = null,
    val client: FileManifest? = null,
    val testModeKey: String? = null,
    val message: String? = null,
) {
    val parsedStatus: ProtocolStatus get() = ProtocolStatus.fromWire(status)
}
