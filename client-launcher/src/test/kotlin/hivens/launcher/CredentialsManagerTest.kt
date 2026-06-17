package hivens.launcher

import dev.hivens.libvault.SecretVault
import dev.hivens.libvault.VaultTier
import hivens.core.data.SessionData
import hivens.core.security.IKeyringStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the libvault-backed CredentialsManager: secrets in the vault, metadata
 * on disk, and the one-time migration off the legacy keyring/AES store. Uses an
 * in-memory [FakeVault] (mirrors libvault's own MemoryVault) and, for the
 * migration cases, a real [LegacyCredentialsManager] over an in-memory
 * [FakeKeyring].
 */
class CredentialsManagerTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private lateinit var workDir: Path
    private lateinit var vault: FakeVault
    private lateinit var legacyKeyring: FakeKeyring
    private lateinit var manager: CredentialsManager

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("nexira-creds-test-")
        vault = FakeVault()
        legacyKeyring = FakeKeyring()
        // The provider builds a legacy reader over the same workDir + keyring,
        // so the migration tests can seed via a real LegacyCredentialsManager.
        manager = CredentialsManager(workDir, json, vault) {
            LegacyCredentialsManager(workDir, json, legacyKeyring)
        }
    }

    @AfterTest
    fun teardown() {
        Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun session(password: String? = "secret-pw", accessToken: String = "fake-game-token") = SessionData(
        playerName = "ChaosA",
        accessToken = accessToken,
        uuid = "550e8400e29b41d4a716446655440000",
        uid = "1",
        cachedPassword = password,
        status = null,
    )

    private fun fileJson(): JsonObject =
        json.parseToJsonElement(Files.readString(workDir / "credentials.json")).jsonObject

    // ── save() ───────────────────────────────────────────────────────────────

    @Test
    fun `save stores secrets in the vault and only metadata on disk`() {
        manager.save(session())

        assertEquals("secret-pw", vault.entries["password"]?.decodeToString())
        assertEquals("fake-game-token", vault.entries["accessToken"]?.decodeToString())

        val obj = fileJson()
        assertEquals("ChaosA", obj["username"]?.jsonPrimitive?.contentOrNull)
        assertEquals(5, obj["version"]?.jsonPrimitive?.int)
        // No secret material ever touches the file.
        assertNull(obj["accessToken"], "accessToken must not be on disk")
        assertNull(obj["encryptedPassword"], "no ciphertext on disk")
        assertNull(obj["keyringHasPassword"], "no legacy flags written")
    }

    @Test
    fun `save with null password -- accessToken stored, password dropped from the vault`() {
        vault.entries["password"] = "stale".toByteArray() // pretend a prior password lingered
        manager.save(session(password = null))

        assertNull(vault.entries["password"], "null password must clear the vault entry")
        assertEquals("fake-game-token", vault.entries["accessToken"]?.decodeToString())
    }

    @Test
    fun `save with blank accessToken -- no-op, no file, empty vault`() {
        manager.save(session(accessToken = ""))
        assertFalse(Files.exists(workDir / "credentials.json"))
        assertTrue(vault.entries.isEmpty())
    }

    // ── load() ───────────────────────────────────────────────────────────────

    @Test
    fun `load round-trips through the vault`() {
        manager.save(session())
        val loaded = CredentialsManager(workDir, json, vault) {
            LegacyCredentialsManager(workDir, json, legacyKeyring)
        }.load()

        assertNotNull(loaded)
        assertEquals("ChaosA", loaded.playerName)
        assertEquals("secret-pw", loaded.cachedPassword)
        assertEquals("fake-game-token", loaded.accessToken)
    }

    @Test
    fun `load with accessToken missing from the vault -- returns null (session gone)`() {
        manager.save(session())
        vault.entries.remove("accessToken")
        assertNull(manager.load(), "no accessToken = unusable session")
    }

    @Test
    fun `load with password missing -- session with null cachedPassword`() {
        manager.save(session())
        vault.entries.remove("password")
        val loaded = manager.load()
        assertNotNull(loaded)
        assertEquals("fake-game-token", loaded.accessToken)
        assertNull(loaded.cachedPassword)
    }

    @Test
    fun `load with no file -- returns null`() {
        assertNull(manager.load())
    }

    @Test
    fun `load with malformed file -- returns null instead of throwing`() {
        Files.writeString(workDir / "credentials.json", "not valid json {{{")
        assertNull(manager.load())
    }

    // ── clear() ──────────────────────────────────────────────────────────────

    @Test
    fun `clear wipes the vault entries and the file`() {
        manager.save(session())
        manager.clear()
        assertFalse(Files.exists(workDir / "credentials.json"))
        assertNull(vault.entries["password"])
        assertNull(vault.entries["accessToken"])
    }

    @Test
    fun `clear is idempotent`() {
        manager.save(session())
        manager.clear()
        manager.clear()
        assertFalse(Files.exists(workDir / "credentials.json"))
    }

    // ── migration from legacy storage ─────────────────────────────────────────

    @Test
    fun `migrates a v4 keyring-mode file into the vault and purges old entries`() {
        // Seed: a real legacy store puts the secrets in the keyring + a v4 file.
        LegacyCredentialsManager(workDir, json, legacyKeyring).save(session())
        assertEquals("secret-pw", legacyKeyring.entries["io.github.kitty_hivens.AuraLauncher::password"])
        assertEquals(4, fileJson()["version"]?.jsonPrimitive?.int)

        val loaded = manager.load()
        assertNotNull(loaded)
        assertEquals("fake-game-token", loaded.accessToken)
        assertEquals("secret-pw", loaded.cachedPassword)

        // Secrets now in the vault, file stamped v5, legacy keyring purged.
        assertEquals("fake-game-token", vault.entries["accessToken"]?.decodeToString())
        assertEquals(5, fileJson()["version"]?.jsonPrimitive?.int)
        assertTrue(legacyKeyring.entries.isEmpty(), "old keyring entries purged after migration")

        // Second load is pure vault -- no legacy left to read.
        assertEquals("fake-game-token", manager.load()?.accessToken)
    }

    @Test
    fun `migrates a v4 file-mode (AES) file into the vault`() {
        // Keyring refuses writes -> legacy store falls back to its AES file.
        legacyKeyring.failStore = true
        LegacyCredentialsManager(workDir, json, legacyKeyring).save(session())
        assertTrue(legacyKeyring.entries.isEmpty(), "secrets went to the AES file, not the keyring")

        val loaded = manager.load()
        assertNotNull(loaded)
        assertEquals("secret-pw", loaded.cachedPassword)
        assertEquals("fake-game-token", loaded.accessToken)

        assertEquals(5, fileJson()["version"]?.jsonPrimitive?.int)
        assertEquals("secret-pw", vault.entries["password"]?.decodeToString())
    }

    @Test
    fun `legacy file with unrecoverable secrets -- returns null and stamps to v5`() {
        // A v4 keyring-mode file whose keyring entries are gone (wiped daemon).
        LegacyCredentialsManager(workDir, json, legacyKeyring).save(session())
        legacyKeyring.entries.clear() // secrets unrecoverable

        assertNull(manager.load(), "no recoverable secret -> re-login")
        // File stamped to v5 so the next load doesn't re-probe legacy storage.
        assertEquals(5, fileJson()["version"]?.jsonPrimitive?.int)
    }

    // ── Fakes ─────────────────────────────────────────────────────────────────

    /** In-memory [SecretVault], the libvault analogue of the old FakeKeyring. */
    private class FakeVault : SecretVault {
        val entries: MutableMap<String, ByteArray> = mutableMapOf()
        override val tier: VaultTier = VaultTier.Memory
        override val backend: String = "fake (test)"

        override fun store(key: String, secret: ByteArray): Boolean {
            entries[key] = secret.copyOf()
            return true
        }

        override fun retrieve(key: String): ByteArray? = entries[key]?.copyOf()

        override fun delete(key: String): Boolean {
            entries.remove(key)
            return true
        }

        override fun contains(key: String): Boolean = entries.containsKey(key)

        override fun close() {}
    }

    /**
     * In-memory [IKeyringStorage] for seeding the legacy store in migration
     * tests. `failStore = true` forces the legacy AES-file path.
     */
    private class FakeKeyring : IKeyringStorage {
        val entries: MutableMap<String, String> = mutableMapOf()
        var failStore: Boolean = false

        private fun key(service: String, account: String) = "$service::$account"

        override fun isAvailable(): Boolean = true

        override fun store(service: String, account: String, secret: String): Boolean {
            if (failStore) return false
            entries[key(service, account)] = secret
            return true
        }

        override fun retrieve(service: String, account: String): String? = entries[key(service, account)]

        override fun clear(service: String, account: String): Boolean = entries.remove(key(service, account)) != null
    }
}
