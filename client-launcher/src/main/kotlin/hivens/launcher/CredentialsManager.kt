package hivens.launcher

import hivens.core.data.SessionData
import hivens.core.security.IKeyringStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Secure credential storage with OS-keyring primary + AES-256-GCM file
 * fallback. Two independent sensitive fields -- password and accessToken --
 * are each handled through the same per-secret chain.
 *
 * Per-secret storage hierarchy (most-to-least preferred):
 *
 *  1. **OS keyring** (libsecret on Linux, Credential Manager / DPAPI
 *     on Windows, Keychain on macOS -- all three via Project Panama).
 *     The plaintext lives here when [IKeyringStorage.isAvailable] and
 *     `store()` succeed. The credentials JSON file then carries
 *     `keyringHasX=true` and a null `encryptedX` field -- keyring is
 *     the sole source of the secret.
 *
 *  2. **AES-256-GCM file** (`credentials.json`). Activated when keyring
 *     is unreachable (no daemon, no library, no permission). Encryption
 *     key is derived from a machine-specific seed via PBKDF2 -- the
 *     classic "casual file-copy" defense, not real protection.
 *
 *  3. **Legacy plaintext** -- read-only path for older format files,
 *     migrated to v4 on the next successful save. Two legacy entries
 *     supported: v1 `savedPasswordBase64`, and v3 plaintext `accessToken`.
 *
 * The credentials file ALWAYS exists when there's a session and stays
 * the source of truth for `username` / `uuid` / `uid` and the
 * "which storage mode is active" flags. Only the secret ciphertexts move
 * between locations based on keyring availability -- per-secret, so a
 * keyring outage for one doesn't necessarily force the other into the
 * file (though in practice both succeed or fail together with the same
 * daemon).
 *
 * Load behavior: a session whose accessToken cannot be resolved
 * (keyring entry wiped, decryption failed, no legacy fallback) returns
 * null from [load], cleanly triggering the re-login path. A session whose
 * password is missing but accessToken survived returns a SessionData with
 * `cachedPassword=null` -- the user can still play; re-login is requested
 * the next time the password is actually needed.
 */
class CredentialsManager(
    workDir: Path,
    private val json: Json,
    private val keyring: IKeyringStorage,
) {
    private val log = LoggerFactory.getLogger(CredentialsManager::class.java)
    private val credentialsFile = workDir.resolve("credentials.json")

    companion object {
        private const val AES_KEY_LENGTH = 256
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val PBKDF2_ITERATIONS = 65536
        // PBKDF2 static-salt component for the v2/v3 envelope. Kept
        // verbatim across the Aura -> Nexira rebrand: rotating it would
        // invalidate every existing user's saved-credentials envelope.
        private const val SALT = "Aura_v2_salt"

        // Keyring identity. SERVICE is the launcher-wide brand scope;
        // ACCOUNT_* is the per-secret name inside that scope. Each
        // sensitive credential gets its own account so they're
        // independently retrievable, rotatable, and clearable.
        // Multi-account support (currently SmartyCraft is single-account
        // per launcher install) would extend this with the username.
        //
        // Service name kept verbatim across rebrand so an existing
        // user's keyring entry (saved under Aura) stays readable under
        // Nexira -- the underlying secret is the same.
        private const val KEYRING_SERVICE = "io.github.kitty_hivens.AuraLauncher"
        private const val KEYRING_ACCOUNT_PASSWORD = "password"
        private const val KEYRING_ACCOUNT_ACCESS_TOKEN = "accessToken"
    }

    @Serializable
    private data class SavedCredentials(
        val username: String,
        val uuid: String,
        val uid: String? = null,

        // ── Password storage (added in v3) ───────────────────────────────
        /**
         * True when the password lives in the OS keyring under
         * (KEYRING_SERVICE, KEYRING_ACCOUNT_PASSWORD). When true,
         * [encryptedPassword] and [passwordIv] are null.
         */
        val keyringHasPassword: Boolean = false,
        /** Encrypted password (AES-256-GCM, Base64-encoded ciphertext). */
        val encryptedPassword: String? = null,
        /** IV for AES-GCM decryption (Base64-encoded). */
        val passwordIv: String? = null,

        // ── Access-token storage (added in v4) ───────────────────────────
        /**
         * True when the accessToken lives in the OS keyring under
         * (KEYRING_SERVICE, KEYRING_ACCOUNT_ACCESS_TOKEN). When true,
         * [encryptedAccessToken] and [accessTokenIv] are null.
         */
        val keyringHasAccessToken: Boolean = false,
        /** Encrypted accessToken (AES-256-GCM, Base64-encoded ciphertext). */
        val encryptedAccessToken: String? = null,
        /** IV for AES-GCM decryption of [encryptedAccessToken] (Base64-encoded). */
        val accessTokenIv: String? = null,

        /**
         * @deprecated Legacy plaintext accessToken from v3 and earlier.
         * Read-only -- populated only when loading older files. Always
         * null in v4+ writes; the next save() migrates the value into
         * either the keyring or [encryptedAccessToken].
         */
        val accessToken: String? = null,
        /** @deprecated Legacy Base64-encoded password -- read-only for migration */
        val savedPasswordBase64: String? = null,

        /** Format version: 1 = Base64 password, 2 = AES-GCM password, 3 = keyring-or-AES-GCM password + plaintext accessToken, 4 = keyring-or-AES-GCM for both */
        val version: Int = 4
    )

    fun save(session: SessionData) {
        // Skip when there's nothing real to save -- accessToken is the
        // canary for "auth actually completed" since blank means we
        // never got a session back.
        if (session.accessToken.isBlank()) return

        try {
            // Each secret tries keyring first; on failure falls back to
            // AES-GCM file. Independent per-secret so a transient
            // keyring failure for one doesn't force both into the file.
            // (Practically speaking they tend to succeed or fail
            // together -- same daemon -- but the modeling is cleaner.)
            val passwordInKeyring = session.cachedPassword?.let { pw ->
                keyring.store(KEYRING_SERVICE, KEYRING_ACCOUNT_PASSWORD, pw)
            } ?: false

            val accessTokenInKeyring = keyring.store(
                KEYRING_SERVICE, KEYRING_ACCOUNT_ACCESS_TOKEN, session.accessToken,
            )

            val (passwordCipher, passwordIv) = if (passwordInKeyring) null to null
                else encryptString(session.cachedPassword)
            val (accessTokenCipher, accessTokenIv) = if (accessTokenInKeyring) null to null
                else encryptString(session.accessToken)

            val data = SavedCredentials(
                username = session.playerName,
                uuid = session.uuid,
                uid = session.uid,
                keyringHasPassword = passwordInKeyring,
                encryptedPassword = passwordCipher,
                passwordIv = passwordIv,
                keyringHasAccessToken = accessTokenInKeyring,
                encryptedAccessToken = accessTokenCipher,
                accessTokenIv = accessTokenIv,
                accessToken = null,           // never written in v4+ (legacy field only for reads)
                savedPasswordBase64 = null,   // ditto
                version = 4,
            )

            if (credentialsFile.parent != null) Files.createDirectories(credentialsFile.parent)

            val text = json.encodeToString(data)
            Files.writeString(credentialsFile, text)
            log.info(
                "Credentials saved (password: {}, accessToken: {})",
                if (passwordInKeyring) "keyring" else "AES-256-GCM file",
                if (accessTokenInKeyring) "keyring" else "AES-256-GCM file",
            )

        } catch (e: Exception) {
            log.error("Could not save credentials", e)
        }
    }

    fun load(): SessionData? {
        if (!Files.exists(credentialsFile)) return null

        return try {
            val text = Files.readString(credentialsFile)
            val data = json.decodeFromString<SavedCredentials>(text)

            val password = resolvePassword(data)
            val accessToken = resolveAccessToken(data)

            // accessToken is load-blocking -- without it the launcher cannot
            // launch the game, so a missing/decryption-failed accessToken
            // means the session is effectively gone and the user must
            // re-login. Returning null here cleanly triggers that path in
            // the calling controller.
            if (accessToken.isNullOrBlank()) {
                log.warn(
                    "credentials present but accessToken could not be resolved " +
                        "(keyring entry wiped? AES decryption failed?) -- treating session as gone",
                )
                return null
            }

            SessionData(
                playerName = data.username,
                accessToken = accessToken,
                uuid = data.uuid,
                uid = data.uid ?: "",
                cachedPassword = password,
                status = null,
            )
        } catch (e: Exception) {
            log.error("Error reading credentials.json file", e)
            null
        }
    }

    private fun resolvePassword(data: SavedCredentials): String? = when {
        // v3+ keyring-mode: password lives in the OS keyring.
        data.keyringHasPassword -> keyring.retrieve(KEYRING_SERVICE, KEYRING_ACCOUNT_PASSWORD).also {
            if (it == null) {
                log.warn(
                    "credentials file marks keyring-resident password, " +
                        "but keyring lookup returned null -- re-login may be required",
                )
            }
        }

        // v3+/v2 file-mode: AES-GCM ciphertext on disk.
        data.encryptedPassword != null && data.passwordIv != null ->
            decryptString(data.encryptedPassword, data.passwordIv)

        // v1 legacy: Base64 -- implicit migration on next save.
        data.savedPasswordBase64 != null -> {
            log.info("Migrating password from v1 Base64 to keyring-or-AES")
            String(Base64.getDecoder().decode(data.savedPasswordBase64))
        }

        else -> null
    }

    private fun resolveAccessToken(data: SavedCredentials): String? = when {
        // v4 keyring-mode.
        data.keyringHasAccessToken -> keyring.retrieve(KEYRING_SERVICE, KEYRING_ACCOUNT_ACCESS_TOKEN).also {
            if (it == null) {
                log.warn(
                    "credentials file marks keyring-resident accessToken, " +
                        "but keyring lookup returned null",
                )
            }
        }

        // v4 file-mode: AES-GCM ciphertext on disk.
        data.encryptedAccessToken != null && data.accessTokenIv != null ->
            decryptString(data.encryptedAccessToken, data.accessTokenIv)

        // v3 and earlier: plaintext accessToken. Reads transparently;
        // on the next save() it gets migrated into the keyring or
        // encrypted in the file.
        data.accessToken != null -> {
            log.info("Migrating accessToken from legacy plaintext to keyring-or-AES on next save")
            data.accessToken
        }

        else -> null
    }

    fun clear() {
        // Wipe both keyring entries and the file unconditionally. Each
        // entry could independently be the source of an active credential
        // depending on its mode; missing one would leave stale secrets in
        // either the keyring or the file.
        runCatching { keyring.clear(KEYRING_SERVICE, KEYRING_ACCOUNT_PASSWORD) }
            .onFailure { log.warn("Failed to clear keyring password entry", it) }
        runCatching { keyring.clear(KEYRING_SERVICE, KEYRING_ACCOUNT_ACCESS_TOKEN) }
            .onFailure { log.warn("Failed to clear keyring accessToken entry", it) }
        try {
            Files.deleteIfExists(credentialsFile)
        } catch (e: IOException) {
            log.warn("Failed to delete credentials file", e)
        }
    }

    // ── AES-256-GCM Encryption ─────────────────────────────────────────────
    //
    // Generic encrypt/decrypt helpers used by both the password and the
    // accessToken file-fallback paths. Each call generates a fresh IV so
    // two values encrypted with the same machine-derived key remain
    // distinguishable on disk.

    private fun encryptString(plaintext: String?): Pair<String?, String?> {
        if (plaintext == null) return null to null

        val key = deriveKey()
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        return Base64.getEncoder().encodeToString(ciphertext) to
                Base64.getEncoder().encodeToString(iv)
    }

    private fun decryptString(encryptedBase64: String, ivBase64: String): String? {
        return try {
            val key = deriveKey()
            val iv = Base64.getDecoder().decode(ivBase64)
            val ciphertext = Base64.getDecoder().decode(encryptedBase64)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            log.error("Failed to decrypt encrypted value", e)
            null
        }
    }

    /**
     * Derives a machine-specific AES key via PBKDF2 for the file-fallback
     * encryption path. The seed combines OS username, user.home, and
     * os.name -- different machines / users will have different keys.
     * Not perfect (no TPM/Keyring), but prevents casual file-copy attacks.
     * Only used when the keyring path is unavailable; the keyring is the
     * preferred storage when it works.
     */
    private fun deriveKey(): SecretKeySpec {
        val machineSeed = buildString {
            append(System.getProperty("user.name", "unknown"))
            append("|")
            append(System.getProperty("user.home", "/"))
            append("|")
            append(System.getProperty("os.name", ""))
            append("|")
            append(System.getProperty("os.arch", ""))
        }

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(
            machineSeed.toCharArray(),
            SALT.toByteArray(Charsets.UTF_8),
            PBKDF2_ITERATIONS,
            AES_KEY_LENGTH
        )
        val secret = factory.generateSecret(spec)
        return SecretKeySpec(secret.encoded, "AES")
    }
}
