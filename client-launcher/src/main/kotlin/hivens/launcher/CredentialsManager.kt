package hivens.launcher

import hivens.core.data.SessionData
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
 * Secure credential storage.
 *
 * Replaces Base64 "protection" with AES-256-GCM encryption.
 * The key is derived from a machine-specific seed (MAC + username + OS)
 * via PBKDF2. Not OS Keyring, but significantly better than plaintext/Base64.
 *
 * Migration: reads old Base64 format transparently, re-saves encrypted.
 */
class CredentialsManager(
    workDir: Path,
    private val json: Json
) {
    private val log = LoggerFactory.getLogger(CredentialsManager::class.java)
    private val credentialsFile = workDir.resolve("credentials.json")

    companion object {
        private const val AES_KEY_LENGTH = 256
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val PBKDF2_ITERATIONS = 65536
        private const val SALT = "AuraLauncher_v2_salt" // Static salt component
    }

    @Serializable
    private data class SavedCredentials(
        val username: String,
        val accessToken: String,
        val uuid: String,
        val uid: String? = null,
        /** Encrypted password (AES-256-GCM, Base64-encoded ciphertext) */
        val encryptedPassword: String? = null,
        /** IV for AES-GCM decryption (Base64-encoded) */
        val passwordIv: String? = null,
        /** @deprecated Legacy Base64-encoded password — read-only for migration */
        val savedPasswordBase64: String? = null,
        /** Format version: 1 = Base64 (legacy), 2 = AES-GCM */
        val version: Int = 2
    )

    fun save(session: SessionData) {
        // We save only if the user requested or the session is valid
        if (session.accessToken.isBlank()) return

        try {
            val (encrypted, iv) = encryptPassword(session.cachedPassword)

            val data = SavedCredentials(
                username = session.playerName,
                accessToken = session.accessToken,
                uuid = session.uuid,
                uid = session.uid,
                encryptedPassword = encrypted,
                passwordIv = iv,
                savedPasswordBase64 = null, // No longer store legacy format
                version = 2
            )

            if (credentialsFile.parent != null) Files.createDirectories(credentialsFile.parent)

            val text = json.encodeToString(data)
            Files.writeString(credentialsFile, text)
            log.info("Credentials saved (AES-256-GCM encrypted)")

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
                // v2: AES-GCM encrypted
                data.version >= 2 && data.encryptedPassword != null && data.passwordIv != null -> {
                    decryptPassword(data.encryptedPassword, data.passwordIv)
                }
                // v1 (legacy): Base64 — migrate on next save
                data.savedPasswordBase64 != null -> {
                    log.info("Migrating credentials from Base64 to AES-GCM")
                    String(Base64.getDecoder().decode(data.savedPasswordBase64))
                }
                else -> null
            }

            // Restoring SessionData.
            // The remaining fields (manifest, balance) will be updated when the profile is updated.
            SessionData(
                playerName = data.username,
                accessToken = data.accessToken,
                uuid = data.uuid,
                uid = data.uid ?: "",
                cachedPassword = password,
                status = null
            )
        } catch (e: Exception) {
            log.error("Error reading credentials.json file", e)
            null
        }
    }

    fun clear() {
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
     * Derives a machine-specific AES key via PBKDF2.
     * The seed combines OS username, user.home, and os.name —
     * different machines / users will have different keys.
     * Not perfect (no TPM/Keyring), but prevents casual file-copy attacks.
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
