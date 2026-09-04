package hivens.auth

import dev.hivens.libvault.SecretVault
import hivens.core.io.writeStringOwnerOnly
import hivens.core.api.interfaces.ICredentialStore
import hivens.core.data.SessionData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Provider-keyed multi-account credential storage on top of [libvault][SecretVault].
 *
 * Each account's two/three secrets -- accessToken, optional password (SmartyCraft),
 * optional refreshToken (Microsoft) -- live in the [vault] under composite keys
 * `"<providerId>:<accountId>:<field>"`; `credentials.json` (v6) holds only the
 * non-secret account list plus which one is active. The active account is the one
 * [load] returns (so existing read-only [ICredentialStore] callers are unchanged).
 *
 * `accountId` is the dash-free uuid when present, else the username -- stable
 * across re-logins of the same identity, so a re-login updates rather than
 * duplicates an account.
 *
 * **Migration.** A pre-v6 `credentials.json` is migrated on the first read:
 * - v5 (single-account, flat vault keys "password"/"accessToken") -> one
 *   SmartyCraft account; the flat secrets are re-keyed to composite keys, the v6
 *   file is written AFTER the re-key (crash-safe), then the flat keys are dropped.
 *   Idempotent: an interrupted run re-reads the composite keys and rebuilds the file.
 * - v < 5 -> recovered through [LegacyCredentialsManager] and re-saved as one
 *   SmartyCraft account; orphaned old keyring entries are purged.
 * Migration writes the vault + file directly (never via [saveAccount]) so it can't
 * recurse through the same read path.
 *
 * A migration that recovers nothing stamps the v6 format over the old file only
 * when the store it asked actually answered. A store that could not be read leaves
 * the file alone: the stamp is one-way, so writing it on an unreachable keyring
 * would trade a temporary outage for a permanent loss of the account.
 *
 * An offline identity is NOT an account here (it has no secret -- a blank token
 * no-ops [saveAccount]); it is reconstructed from `SettingsData.offlinePlayerName`.
 */
class CredentialsManager(
    workDir: Path,
    private val json: Json,
    private val vault: SecretVault,
    private val legacyProvider: () -> LegacyCredentialsManager,
) : AccountStore {
    private val log = LoggerFactory.getLogger(CredentialsManager::class.java)
    private val credentialsFile = workDir.resolve("credentials.json")

    /**
     * Whether a migration already failed on an unreadable store this run.
     *
     * Without it the retry that keeps the old file alive would run again on every
     * read, and the read paths include three that sit inside composition, so a
     * profile whose keyring is down would pay a Secret Service probe per frame.
     * One attempt per launch is what the retry is worth. A store that comes back
     * mid-session is picked up on the next start, which is when the account was
     * going to be recovered anyway.
     */
    @Volatile
    private var legacyUnreadableThisRun = false

    // ── ICredentialStore: the active session ──────────────────────────────────

    override fun load(): SessionData? {
        val file = readAccountsFile() ?: return null
        val active = file.activeAccountId ?: return null
        return loadSession(active)
    }

    // ── multi-account API ───────────────────────────────────────────────────────

    override fun listAccounts(): List<StoredAccount> =
        (readAccountsFile()?.accounts ?: emptyList()).map {
            StoredAccount(it.providerId, it.accountId, it.username, it.uuid, it.displayName)
        }

    override fun activeAccountId(): String? = readAccountsFile()?.activeAccountId

    override fun saveAccount(session: SessionData, providerId: String) {
        if (session.accessToken.isBlank()) return
        val accountId = accountIdFor(session)
        val account = SavedAccount(
            providerId = providerId,
            accountId = accountId,
            username = session.playerName,
            uuid = session.uuid,
            uid = session.uid.ifBlank { null },
            displayName = session.playerName,
            // Sticky: a re-login that happens to arrive without the flag must not
            // clear what an earlier second factor established. Releasing it takes
            // the explicit [clearTwoFactor] instead.
            twoFactor = session.twoFactor ||
                readAccountsFile()?.accounts?.firstOrNull {
                    it.accountId == accountId && it.providerId == providerId
                }?.twoFactor == true,
        )
        val current = readAccountsFile() ?: SavedAccountsFile()
        val merged = current.accounts.filterNot { it.accountId == accountId && it.providerId == providerId } + account
        storeSecrets(providerId, accountId, session)
        writeAccountsFile(current.copy(version = CURRENT_VERSION, activeAccountId = accountId, accounts = merged))
        log.info("Saved account {} ({}) -- vault tier={}", accountId, providerId, vault.tier)
    }

    override fun save(session: SessionData) = saveAccount(session, inferProviderId(session))

    override fun clearTwoFactor(providerId: String) {
        val file = readAccountsFile() ?: return
        val updated = file.accounts.map {
            if (it.providerId == providerId && it.twoFactor) it.copy(twoFactor = false) else it
        }
        if (updated == file.accounts) return
        writeAccountsFile(file.copy(accounts = updated))
        log.info("released the two-factor gate on {} -- the provider asked for no second factor", providerId)
    }

    /**
     * The stored session for [providerId]'s account, or null when not signed in
     * with that provider. The launch flow uses this to pick the account matching
     * the content's required provider (multi-active: SC + Microsoft + offline are
     * all live at once). Returns the first account of the provider -- one per
     * provider is the norm; a future multiple-of-same-provider case would resolve
     * the pick at the call site.
     */
    override fun accountFor(providerId: String): SessionData? {
        val account = readAccountsFile()?.accounts?.firstOrNull { it.providerId == providerId } ?: return null
        return loadSession(account.accountId)
    }

    override fun primarySession(preferredProviderId: String?): SessionData? {
        val accounts = readAccountsFile()?.accounts ?: return null
        if (preferredProviderId != null) {
            accounts.firstOrNull { it.providerId == preferredProviderId }
                ?.let { account -> loadSession(account.accountId)?.let { return it } }
        }
        for (account in accounts.sortedBy { facePriorityIndex(it.providerId) }) {
            loadSession(account.accountId)?.let { return it }
        }
        return null
    }

    private fun facePriorityIndex(providerId: String): Int =
        FACE_PRIORITY.indexOf(providerId).let { if (it < 0) FACE_PRIORITY.size else it }

    override fun loadSession(accountId: String): SessionData? {
        val account = readAccountsFile()?.accounts?.firstOrNull { it.accountId == accountId } ?: return null
        val accessToken = secret(account, FIELD_ACCESS_TOKEN)
        if (accessToken.isNullOrBlank()) {
            log.warn("account {} has metadata but no accessToken in the vault -- treating as gone", accountId)
            return null
        }
        return SessionData(
            playerName = account.username,
            uuid = account.uuid,
            uid = account.uid ?: "",
            accessToken = accessToken,
            cachedPassword = secret(account, FIELD_PASSWORD),
            refreshToken = secret(account, FIELD_REFRESH_TOKEN),
            twoFactor = account.twoFactor,
            status = null,
        )
    }

    override fun setActive(accountId: String) {
        val file = readAccountsFile() ?: return
        if (file.accounts.none { it.accountId == accountId }) return
        writeAccountsFile(file.copy(activeAccountId = accountId))
    }

    override fun removeAccount(accountId: String) {
        val file = readAccountsFile() ?: return
        val account = file.accounts.firstOrNull { it.accountId == accountId } ?: return
        deleteSecrets(account.providerId, accountId)
        val remaining = file.accounts.filterNot { it.accountId == accountId }
        if (remaining.isEmpty()) {
            deleteFile()
        } else {
            val newActive = if (file.activeAccountId == accountId) remaining.first().accountId else file.activeAccountId
            writeAccountsFile(file.copy(activeAccountId = newActive, accounts = remaining))
        }
    }

    override fun clear() {
        readAccountsFile()?.accounts?.forEach { deleteSecrets(it.providerId, it.accountId) }
        // Drop any lingering legacy flat keys too.
        vault.delete(LEGACY_KEY_ACCESS_TOKEN)
        vault.delete(LEGACY_KEY_PASSWORD)
        deleteFile()
    }

    // ── persistence + migration ─────────────────────────────────────────────────

    /** Reads the v6 accounts file, migrating a pre-v6 file in place on first read. */
    private fun readAccountsFile(): SavedAccountsFile? {
        if (!Files.exists(credentialsFile)) return null
        val text = runCatching { Files.readString(credentialsFile) }.getOrElse { return null }
        val file = runCatching { json.decodeFromString(SavedAccountsFile.serializer(), text) }.getOrElse {
            log.warn("credentials.json unreadable -- treating as no saved accounts")
            return null
        }
        if (file.version >= CURRENT_VERSION) return file
        if (legacyUnreadableThisRun) return SavedAccountsFile()
        return migrate(text)
    }

    private fun migrate(rawV5OrOlder: String): SavedAccountsFile {
        val v5 = runCatching { json.decodeFromString(SavedMetadataV5.serializer(), rawV5OrOlder) }.getOrNull()
        return if (v5 != null && v5.version >= 5) migrateFromV5(v5) else migrateFromLegacy()
    }

    /** v5 (single account, flat vault keys) -> v6 (one SmartyCraft account, composite keys). */
    private fun migrateFromV5(v5: SavedMetadataV5): SavedAccountsFile {
        val accountId = v5.uuid.ifBlank { v5.username }
        // Prefer the flat key; fall back to a composite key already written by an
        // interrupted prior run (idempotent re-key).
        val token = vault.retrieve(LEGACY_KEY_ACCESS_TOKEN)?.decodeToString()?.takeIf { it.isNotBlank() }
            ?: vault.retrieve(compositeKey(PROVIDER_SMARTYCRAFT, accountId, FIELD_ACCESS_TOKEN))?.decodeToString()
        if (token.isNullOrBlank()) {
            // No stamp. The v5 file carries the only copy of the username, the uuid
            // and the uid, and the flat vault keys are dropped on the success path
            // alone, so a vault that answered nothing this run (a tier that opened
            // where the secret was not written) would otherwise cost the identity
            // those secrets belong to.
            legacyUnreadableThisRun = true
            log.warn("v5 credentials present and the vault returned no token -- leaving them for the next launch")
            return SavedAccountsFile()
        }
        vault.store(compositeKey(PROVIDER_SMARTYCRAFT, accountId, FIELD_ACCESS_TOKEN), token.toByteArray())
        val pass = vault.retrieve(LEGACY_KEY_PASSWORD)?.decodeToString()
            ?: vault.retrieve(compositeKey(PROVIDER_SMARTYCRAFT, accountId, FIELD_PASSWORD))?.decodeToString()
        if (pass != null) vault.store(compositeKey(PROVIDER_SMARTYCRAFT, accountId, FIELD_PASSWORD), pass.toByteArray())

        val account = SavedAccount(PROVIDER_SMARTYCRAFT, accountId, v5.username, v5.uuid, v5.uid, v5.username)
        val file = SavedAccountsFile(CURRENT_VERSION, accountId, listOf(account))
        writeAccountsFile(file)                       // stamp v6 only AFTER the re-key
        vault.delete(LEGACY_KEY_ACCESS_TOKEN)
        vault.delete(LEGACY_KEY_PASSWORD)
        log.info("migrated v5 credentials to the v6 provider-keyed store")
        return file
    }

    /** v < 5 -> recover via the legacy keyring/AES store, re-save as one SmartyCraft v6 account. */
    private fun migrateFromLegacy(): SavedAccountsFile {
        val legacy = legacyProvider()
        val recovered = when (val recovery = legacy.recover()) {
            is LegacyRecovery.Recovered -> recovery.session
            LegacyRecovery.Absent -> {
                // The store answered and holds nothing, so the old file describes an
                // account that cannot come back. Stamping the new format over it is
                // what stops this path being retried on every read for the rest of
                // the install's life.
                log.info("legacy credentials unrecoverable -- re-login required")
                return SavedAccountsFile().also { writeAccountsFile(it) }
            }
            LegacyRecovery.Unavailable -> {
                // Read failed rather than came back empty, so the secrets may still
                // be there: a locked Secret Service, a keyring daemon that has not
                // started yet, a home that moved. Writing here would replace the only
                // record of the account with an empty one, and the version stamp
                // means nothing would ever look at the legacy path again.
                legacyUnreadableThisRun = true
                log.warn("legacy credentials could not be read -- leaving them for the next launch")
                return SavedAccountsFile()
            }
        }
        val accountId = accountIdFor(recovered)
        storeSecrets(PROVIDER_SMARTYCRAFT, accountId, recovered)
        val account = SavedAccount(
            PROVIDER_SMARTYCRAFT, accountId, recovered.playerName, recovered.uuid,
            recovered.uid.ifBlank { null }, recovered.playerName,
        )
        val file = SavedAccountsFile(CURRENT_VERSION, accountId, listOf(account))
        writeAccountsFile(file)
        legacy.clearKeyringOnly()
        log.info("migrated legacy credentials to the v6 provider-keyed store")
        return file
    }

    private fun storeSecrets(providerId: String, accountId: String, session: SessionData) {
        vault.store(compositeKey(providerId, accountId, FIELD_ACCESS_TOKEN), session.accessToken.toByteArray())
        putOrDelete(compositeKey(providerId, accountId, FIELD_PASSWORD), session.cachedPassword)
        putOrDelete(compositeKey(providerId, accountId, FIELD_REFRESH_TOKEN), session.refreshToken)
    }

    private fun deleteSecrets(providerId: String, accountId: String) {
        vault.delete(compositeKey(providerId, accountId, FIELD_ACCESS_TOKEN))
        vault.delete(compositeKey(providerId, accountId, FIELD_PASSWORD))
        vault.delete(compositeKey(providerId, accountId, FIELD_REFRESH_TOKEN))
    }

    private fun putOrDelete(key: String, value: String?) {
        if (value != null) vault.store(key, value.toByteArray()) else vault.delete(key)
    }

    private fun secret(account: SavedAccount, field: String): String? =
        vault.retrieve(compositeKey(account.providerId, account.accountId, field))?.decodeToString()

    private fun writeAccountsFile(file: SavedAccountsFile) {
        // Owner-only: the file names every account on the machine and, on the
        // pre-vault format, carries the encrypted material and its IV. The
        // process umask leaves it world-readable by default on a typical Linux
        // desktop.
        writeStringOwnerOnly(credentialsFile, json.encodeToString(SavedAccountsFile.serializer(), file))
    }

    private fun deleteFile() {
        try {
            Files.deleteIfExists(credentialsFile)
        } catch (e: IOException) {
            log.warn("Failed to delete credentials file", e)
        }
    }

    private fun accountIdFor(session: SessionData): String = session.uuid.ifBlank { session.playerName }

    private fun inferProviderId(session: SessionData): String =
        if (session.refreshToken != null) PROVIDER_MICROSOFT else PROVIDER_SMARTYCRAFT

    private fun compositeKey(providerId: String, accountId: String, field: String): String =
        "$providerId:$accountId:$field"

    @Serializable
    private data class SavedAccount(
        val providerId: String,
        val accountId: String,
        val username: String = "",
        val uuid: String = "",
        val uid: String? = null,
        val displayName: String = "",
        /**
         * Whether this account answers to a second factor. Persisted because it is a
         * property of the ACCOUNT, not of one sign-in: a session restored without it
         * looks ordinary, and the launcher then re-authenticates before a launch --
         * which on SmartyCraft invalidates the session the user just unlocked with a
         * code. Absent in records written before this field existed, and false is the
         * right reading there: the gate marks the account the first time it is seen.
         */
        val twoFactor: Boolean = false,
    )

    @Serializable
    private data class SavedAccountsFile(
        val version: Int = CURRENT_VERSION,
        val activeAccountId: String? = null,
        val accounts: List<SavedAccount> = emptyList(),
    )

    /** Pre-v6 single-account metadata shape, read only during migration. */
    @Serializable
    private data class SavedMetadataV5(
        val username: String = "",
        val uuid: String = "",
        val uid: String? = null,
        val version: Int = 0,
    )

    private companion object {
        const val CURRENT_VERSION = 6
        const val PROVIDER_SMARTYCRAFT = "smartycraft"
        const val PROVIDER_MICROSOFT = "microsoft"

        // Primary-face precedence: the licensed (Microsoft) account fronts the
        // shell before a SmartyCraft one. Unknown providers sort after both.
        val FACE_PRIORITY = listOf(PROVIDER_MICROSOFT, PROVIDER_SMARTYCRAFT)
        const val FIELD_ACCESS_TOKEN = "accessToken"
        const val FIELD_PASSWORD = "password"
        const val FIELD_REFRESH_TOKEN = "refreshToken"

        // Flat keys written by the v5 store; read once during migration, then dropped.
        const val LEGACY_KEY_ACCESS_TOKEN = "accessToken"
        const val LEGACY_KEY_PASSWORD = "password"
    }
}
