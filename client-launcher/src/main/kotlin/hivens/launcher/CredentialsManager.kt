package hivens.launcher

import dev.hivens.libvault.SecretVault
import hivens.core.api.interfaces.ICredentialStore
import hivens.core.data.SessionData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Credential storage on top of [libvault][SecretVault]. The two sensitive
 * fields -- password and accessToken -- live in the [vault] (OS keyring when
 * available, an AES-256-GCM file otherwise); `credentials.json` holds only the
 * non-secret session metadata (player name, uuid, uid).
 *
 * The vault decides the tier and degrades on its own: a missing or locked
 * keyring falls back to the encrypted file, and a wedged keyring service times
 * out instead of blocking. One consequence of that contract differs from the
 * pre-libvault behavior: when the OS keyring is *locked* at startup, the vault
 * does NOT raise an unlock prompt -- it stores into the software-file tier
 * ([SecretVault.secure] is false) until the keyring is unlocked. Inspect
 * [SecretVault.tier] / [SecretVault.secure] if a caller needs to know.
 *
 * **Migration.** Files written by the old keyring + AES store (version < 5) are
 * migrated on the first [load]: the secrets are recovered through
 * [LegacyCredentialsManager], re-saved through the vault, the file is rewritten
 * to v5, and the orphaned old keyring entries are purged. Fresh installs and
 * already-migrated profiles never construct the legacy reader (no extra keyring
 * probe), since [legacyProvider] is only invoked on a pre-v5 file.
 *
 * Load behavior is unchanged from the caller's view: a session whose accessToken
 * cannot be resolved returns null from [load] (re-login), and a session with a
 * resolved accessToken but no password returns `cachedPassword = null`.
 */
class CredentialsManager internal constructor(
    workDir: Path,
    private val json: Json,
    private val vault: SecretVault,
    private val legacyProvider: () -> LegacyCredentialsManager,
) : ICredentialStore {
    private val log = LoggerFactory.getLogger(CredentialsManager::class.java)
    private val credentialsFile = workDir.resolve("credentials.json")

    private companion object {
        // credentials.json schema version. 1-4 are the legacy keyring/AES
        // formats handled by LegacyCredentialsManager; 5 is metadata-only with
        // the secrets in the vault.
        const val CURRENT_VERSION = 5
        const val KEY_PASSWORD = "password"
        const val KEY_ACCESS_TOKEN = "accessToken"
    }

    /** Non-secret session metadata. The secrets live in [vault], not here. */
    @Serializable
    private data class SavedMetadata(
        val username: String = "",
        val uuid: String = "",
        val uid: String? = null,
        val version: Int = CURRENT_VERSION,
    )

    fun save(session: SessionData) {
        // accessToken is the canary for "auth actually completed"; blank means
        // there is nothing real to persist.
        if (session.accessToken.isBlank()) return

        try {
            writeMetadata(
                SavedMetadata(
                    username = session.playerName,
                    uuid = session.uuid,
                    uid = session.uid,
                    version = CURRENT_VERSION,
                ),
            )

            val tokenStored = vault.store(KEY_ACCESS_TOKEN, session.accessToken.toByteArray(Charsets.UTF_8))
            val passwordStored = session.cachedPassword?.let {
                vault.store(KEY_PASSWORD, it.toByteArray(Charsets.UTF_8))
            } ?: run {
                vault.delete(KEY_PASSWORD) // no password this session -- drop any stale one
                null
            }

            log.info(
                "Credentials saved (vault tier={}, accessToken stored={}, password stored={})",
                vault.tier, tokenStored, passwordStored,
            )
        } catch (e: Exception) {
            log.error("Could not save credentials", e)
        }
    }

    override fun load(): SessionData? {
        if (!Files.exists(credentialsFile)) return null

        val meta = try {
            json.decodeFromString<SavedMetadata>(Files.readString(credentialsFile))
        } catch (e: Exception) {
            log.error("Error reading credentials.json file", e)
            return null
        }

        if (meta.version < CURRENT_VERSION) return migrate(meta)

        val accessToken = vault.retrieve(KEY_ACCESS_TOKEN)?.let { String(it, Charsets.UTF_8) }
        if (accessToken.isNullOrBlank()) {
            log.warn(
                "credentials metadata present but accessToken not in the vault " +
                    "(entry wiped? store failed?) -- treating session as gone",
            )
            return null
        }
        val password = vault.retrieve(KEY_PASSWORD)?.let { String(it, Charsets.UTF_8) }

        return SessionData(
            playerName = meta.username,
            accessToken = accessToken,
            uuid = meta.uuid,
            uid = meta.uid ?: "",
            cachedPassword = password,
            status = null,
        )
    }

    /**
     * One-time migration from a legacy (version < 5) file. Recovers the secrets
     * through the old store, re-saves them via the vault, and purges the orphaned
     * old keyring entries. On unrecoverable secrets the file is still stamped to
     * v5 so the next load doesn't re-run migration (and re-probe the keyring).
     */
    private fun migrate(meta: SavedMetadata): SessionData? {
        val legacy = legacyProvider()
        val recovered = legacy.load()
        if (recovered != null) {
            save(recovered)            // rewrites the file to v5 + stores secrets in the vault
            legacy.clearKeyringOnly()  // drop the now-orphaned old-schema keyring entries
            log.info("migrated saved credentials from legacy storage into the vault")
            return recovered
        }
        // Secrets unrecoverable (keyring entry wiped, decryption failed). Stamp
        // the file forward so we stop probing legacy storage on every load.
        runCatching { writeMetadata(meta.copy(version = CURRENT_VERSION)) }
            .onFailure { log.warn("could not stamp migrated metadata version", it) }
        log.info("legacy credentials present but secrets unrecoverable -- re-login required")
        return null
    }

    fun clear() {
        vault.delete(KEY_PASSWORD)
        vault.delete(KEY_ACCESS_TOKEN)
        try {
            Files.deleteIfExists(credentialsFile)
        } catch (e: IOException) {
            log.warn("Failed to delete credentials file", e)
        }
    }

    private fun writeMetadata(meta: SavedMetadata) {
        credentialsFile.parent?.let { Files.createDirectories(it) }
        Files.writeString(credentialsFile, json.encodeToString(meta))
    }
}
