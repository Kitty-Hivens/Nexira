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
 * fallback.
 *
 * Storage hierarchy (most-to-least preferred):
 *
 *  1. **OS keyring** (libsecret on Linux, follow-ups for Win/Mac).
 *     The plaintext password lives here when [IKeyringStorage.isAvailable]
 *     and `store()` succeed. The credentials JSON file then carries
 *     `keyringHasPassword=true` and a null `encryptedPassword` field —
 *     keyring is the sole source of the secret.
 *
 *  2. **AES-256-GCM file** (`credentials.json`). Activated when keyring
 *     is unreachable (no daemon, no library, no permission). Encryption
 *     key is derived from a machine-specific seed via PBKDF2 — the
 *     classic "casual file-copy" defense, not real protection.
 *
 *  3. **Legacy v1 Base64** — read-only path, migrated to v3 on the next
 *     successful save.
 *
 * The credentials file ALWAYS exists when there's a session; it's the
 * source of truth for username / uuid / uid / accessToken and the
 * "which storage mode is active" flag. Only the password ciphertext
 * moves between locations based on keyring availability.
 *
 * NOT YET in scope (tracked as Vault #1.F): `accessToken` is still
 * stored plaintext in the file. It's derived from the game-token
 * decryption flow and equally sensitive as the password — should also
 * live in the keyring. Separate PR.
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
        private const val SALT = "AuraLauncher_v2_salt" // Static salt component

        // Keyring identity. SERVICE is the launcher-wide brand scope;
        // ACCOUNT_PASSWORD is the per-secret name inside that scope.
        // Multi-account support (currently SmartyCraft is single-account
        // per launcher install) would extend this with the username.
        private const val KEYRING_SERVICE = "io.github.kitty_hivens.AuraLauncher"
        private const val KEYRING_ACCOUNT_PASSWORD = "password"
    }

    @Serializable
    private data class SavedCredentials(
        val username: String,
        // TODO Vault #1.F — accessToken should live in the keyring or be
        // encrypted. Currently plaintext on disk.
        val accessToken: String,
        val uuid: String,
        val uid: String? = null,
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
        /** @deprecated Legacy Base64-encoded password — read-only for migration */
        val savedPasswordBase64: String? = null,
        /** Format version: 1 = Base64 (legacy), 2 = AES-GCM, 3 = keyring-or-AES-GCM */
        val version: Int = 3
    )

    fun save(session: SessionData) {
        // Skip when there's nothing real to save — accessToken is the
        // canary for "auth actually completed" since blank means we
        // never got a session back.
        if (session.accessToken.isBlank()) return

        try {
            // Try keyring first. On success, file omits the ciphertext
            // (sole-source semantics). On failure, fall through to
            // AES-GCM file path so the user doesn't get logged out by
            // a transient daemon outage.
            val storedInKeyring = session.cachedPassword?.let { pw ->
                keyring.store(KEYRING_SERVICE, KEYRING_ACCOUNT_PASSWORD, pw)
            } ?: false

            val data = if (storedInKeyring) {
                SavedCredentials(
                    username = session.playerName,
                    accessToken = session.accessToken,
                    uuid = session.uuid,
                    uid = session.uid,
                    keyringHasPassword = true,
                    encryptedPassword = null,
                    passwordIv = null,
                    savedPasswordBase64 = null,
                    version = 3,
                )
            } else {
                val (encrypted, iv) = encryptPassword(session.cachedPassword)
                SavedCredentials(
                    username = session.playerName,
                    accessToken = session.accessToken,
                    uuid = session.uuid,
                    uid = session.uid,
                    keyringHasPassword = false,
                    encryptedPassword = encrypted,
                    passwordIv = iv,
                    savedPasswordBase64 = null,
                    version = 3,
                )
            }

            if (credentialsFile.parent != null) Files.createDirectories(credentialsFile.parent)

            val text = json.encodeToString(data)
            Files.writeString(credentialsFile, text)
            log.info(
                "Credentials saved (password storage: {})",
                if (storedInKeyring) "keyring" else "AES-256-GCM file",
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

            val password = when {
                // v3 keyring-mode: password lives in the OS keyring.
                data.keyringHasPassword -> {
                    keyring.retrieve(KEYRING_SERVICE, KEYRING_ACCOUNT_PASSWORD).also {
                        if (it == null) {
                            // File says keyring should have it, but the keyring
                            // doesn't — daemon was wiped, user logged out of
                            // their session keyring, or the entry was manually
                            // removed. The session is effectively gone; load
                            // returns the metadata but cachedPassword=null.
                            // The launcher's relogin path triggers when the
                            // user clicks Play and we discover the missing
                            // password.
                            log.warn(
                                "credentials file marks keyring-resident password, " +
                                    "but keyring lookup returned null — re-login required",
                            )
                        }
                    }
                }

                // v3/v2 file-mode: AES-GCM ciphertext on disk.
                data.encryptedPassword != null && data.passwordIv != null -> {
                    decryptPassword(data.encryptedPassword, data.passwordIv)
                }

                // v1 legacy: Base64 — implicit migration on next save.
                data.savedPasswordBase64 != null -> {
                    log.info("Migrating credentials from v1 Base64 to v3 keyring-or-AES")
                    String(Base64.getDecoder().decode(data.savedPasswordBase64))
                }

                else -> null
            }

            // Restoring SessionData. The remaining fields (manifest, balance)
            // get refreshed when the dashboard request fires.
            SessionData(
                playerName = data.username,
                accessToken = data.accessToken,
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

    fun clear() {
        // Wipe both the keyring entry and the file unconditionally. Either
        // could be the source of the active password depending on mode;
        // catching just one would leave a stale credential in the other.
        runCatching { keyring.clear(KEYRING_SERVICE, KEYRING_ACCOUNT_PASSWORD) }
            .onFailure { log.warn("Failed to clear keyring entry", it) }
        try {
            Files.deleteIfExists(credentialsFile)
        } catch (e: IOException) {
            log.warn("Failed to delete credentials file", e)
        }
    }

    // ── AES-256-GCM Encryption ─────────────────────────────────────────────

    private fun encryptPassword(password: String?): Pair<String?, String?> {
        if (password == null) return null to null

        val key = deriveKey()
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))

        return Base64.getEncoder().encodeToString(ciphertext) to
                Base64.getEncoder().encodeToString(iv)
    }

    private fun decryptPassword(encryptedBase64: String, ivBase64: String): String? {
        return try {
            val key = deriveKey()
            val iv = Base64.getDecoder().decode(ivBase64)
            val ciphertext = Base64.getDecoder().decode(encryptedBase64)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            log.error("Failed to decrypt password", e)
            null
        }
    }

    /**
     * Derives a machine-specific AES key via PBKDF2 for the file-fallback
     * encryption path. The seed combines OS username, user.home, and
     * os.name — different machines / users will have different keys.
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
