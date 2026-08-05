package hivens.auth

import dev.hivens.libvault.SecretVault
import dev.hivens.libvault.VaultTier
import hivens.core.data.SessionData
import hivens.core.security.IKeyringStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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
 * Tests the v6 provider-keyed multi-account CredentialsManager: composite-keyed
 * secrets in the vault, an account list on disk, and the migrations off v5
 * (flat keys) and the v4 legacy keyring/AES store. In-memory [FakeVault] +
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
        manager = newManager()
    }

    @AfterTest
    fun teardown() {
        Files.walk(workDir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
    }

    private fun newManager() = CredentialsManager(workDir, json, vault) {
        LegacyCredentialsManager(workDir, json, legacyKeyring)
    }

    private val scUuid = "550e8400e29b41d4a716446655440000"
    private fun scKey(field: String) = "smartycraft:$scUuid:$field"

    private fun session(
        password: String? = "secret-pw",
        accessToken: String = "fake-game-token",
        uuid: String = scUuid,
        playerName: String = "ChaosA",
        refreshToken: String? = null,
    ) = SessionData(
        playerName = playerName,
        accessToken = accessToken,
        uuid = uuid,
        uid = "1",
        cachedPassword = password,
        refreshToken = refreshToken,
        status = null,
    )

    private fun fileJson(): JsonObject =
        json.parseToJsonElement(Files.readString(workDir / "credentials.json")).jsonObject

    private fun firstAccount(): JsonObject = fileJson()["accounts"]!!.jsonArray[0].jsonObject

    // ── save() ───────────────────────────────────────────────────────────────

    @Test
    fun `save stores secrets under composite keys and only metadata on disk`() {
        manager.save(session())

        assertEquals("secret-pw", vault.entries[scKey("password")]?.decodeToString())
        assertEquals("fake-game-token", vault.entries[scKey("accessToken")]?.decodeToString())

        val obj = fileJson()
        assertEquals(6, obj["version"]?.jsonPrimitive?.int)
        assertEquals(scUuid, obj["activeAccountId"]?.jsonPrimitive?.contentOrNull)
        assertEquals("ChaosA", firstAccount()["username"]?.jsonPrimitive?.contentOrNull)
        assertEquals("smartycraft", firstAccount()["providerId"]?.jsonPrimitive?.contentOrNull)
        // No secret material ever touches the file.
        assertNull(obj["accessToken"], "accessToken must not be on disk")
    }

    @Test
    fun `save with null password clears the password key, keeps the token`() {
        vault.entries[scKey("password")] = "stale".toByteArray()
        manager.save(session(password = null))

        assertNull(vault.entries[scKey("password")], "null password must clear the vault entry")
        assertEquals("fake-game-token", vault.entries[scKey("accessToken")]?.decodeToString())
    }

    @Test
    fun `save with blank accessToken is a no-op`() {
        manager.save(session(accessToken = ""))
        assertFalse(Files.exists(workDir / "credentials.json"))
        assertTrue(vault.entries.isEmpty())
    }

    @Test
    fun `save of a Microsoft session stores the refresh token`() {
        manager.saveAccount(session(uuid = "msuuid", refreshToken = "RT", password = null), "microsoft")
        assertEquals("RT", vault.entries["microsoft:msuuid:refreshToken"]?.decodeToString())
        assertEquals("fake-game-token", vault.entries["microsoft:msuuid:accessToken"]?.decodeToString())
    }

    // ── load() ───────────────────────────────────────────────────────────────

    @Test
    fun `load round-trips the active account through the vault`() {
        manager.save(session())
        val loaded = newManager().load()
        assertNotNull(loaded)
        assertEquals("ChaosA", loaded.playerName)
        assertEquals("secret-pw", loaded.cachedPassword)
        assertEquals("fake-game-token", loaded.accessToken)
    }

    @Test
    fun `load with the access token gone returns null`() {
        manager.save(session())
        vault.entries.remove(scKey("accessToken"))
        assertNull(manager.load())
    }

    @Test
    fun `load with the password gone yields a null cachedPassword`() {
        manager.save(session())
        vault.entries.remove(scKey("password"))
        val loaded = manager.load()
        assertNotNull(loaded)
        assertEquals("fake-game-token", loaded.accessToken)
        assertNull(loaded.cachedPassword)
    }

    @Test
    fun `load with no file returns null`() {
        assertNull(manager.load())
    }

    @Test
    fun `load with a malformed file returns null instead of throwing`() {
        Files.writeString(workDir / "credentials.json", "not valid json {{{")
        assertNull(manager.load())
    }

    // ── multi-account ──────────────────────────────────────────────────────────

    @Test
    fun `two accounts coexist, last saved is active, both load`() {
        manager.save(session())                                                  // SC
        manager.saveAccount(session(uuid = "msuuid", playerName = "MsGamer", refreshToken = "RT", password = null), "microsoft")

        assertEquals(2, manager.listAccounts().size)
        assertEquals("msuuid", manager.activeAccountId())
        assertEquals("ChaosA", manager.loadSession(scUuid)?.playerName)
        assertEquals("MsGamer", manager.loadSession("msuuid")?.playerName)
    }

    @Test
    fun `accountFor resolves the session for each provider`() {
        manager.save(session())
        manager.saveAccount(session(uuid = "msuuid", playerName = "MsGamer", refreshToken = "RT"), "microsoft")
        assertEquals("ChaosA", manager.accountFor("smartycraft")?.playerName)
        assertEquals("MsGamer", manager.accountFor("microsoft")?.playerName)
        assertNull(manager.accountFor("offline"))
    }

    @Test
    fun `setActive switches which account load returns`() {
        manager.save(session())
        manager.saveAccount(session(uuid = "msuuid", playerName = "MsGamer", refreshToken = "RT"), "microsoft")
        manager.setActive(scUuid)
        assertEquals("ChaosA", manager.load()?.playerName)
    }

    @Test
    fun `primarySession prefers the licensed Microsoft account over SmartyCraft`() {
        manager.save(session())                                                  // SC saved first
        manager.saveAccount(session(uuid = "msuuid", playerName = "MsGamer", refreshToken = "RT", password = null), "microsoft")
        // Even with SC made active, the licensed account fronts the shell.
        manager.setActive(scUuid)
        assertEquals("MsGamer", manager.primarySession()?.playerName)
    }

    @Test
    fun `primarySession falls back to SmartyCraft when no Microsoft account`() {
        manager.save(session())
        assertEquals("ChaosA", manager.primarySession()?.playerName)
    }

    @Test
    fun `primarySession is null with no accounts`() {
        assertNull(manager.primarySession())
    }

    @Test
    fun `re-saving the same identity upserts rather than duplicates`() {
        manager.save(session())
        manager.save(session(playerName = "ChaosA"))   // same uuid -> same accountId
        assertEquals(1, manager.listAccounts().size)
    }

    @Test
    fun `removeAccount drops its secrets and reassigns active`() {
        manager.save(session())
        manager.saveAccount(session(uuid = "msuuid", playerName = "MsGamer", refreshToken = "RT"), "microsoft")
        manager.removeAccount("msuuid")

        assertEquals(1, manager.listAccounts().size)
        assertNull(vault.entries["microsoft:msuuid:accessToken"])
        assertEquals(scUuid, manager.activeAccountId())
        assertEquals("ChaosA", manager.load()?.playerName)
    }

    // ── clear() ──────────────────────────────────────────────────────────────

    @Test
    fun `clear wipes every account secret and the file`() {
        manager.save(session())
        manager.saveAccount(session(uuid = "msuuid", refreshToken = "RT"), "microsoft")
        manager.clear()
        assertFalse(Files.exists(workDir / "credentials.json"))
        assertTrue(vault.entries.isEmpty())
    }

    @Test
    fun `clear is idempotent`() {
        manager.save(session())
        manager.clear()
        manager.clear()
        assertFalse(Files.exists(workDir / "credentials.json"))
    }

    // ── migration: v5 (flat keys) -> v6 ─────────────────────────────────────────

    @Test
    fun `migrates a v5 flat-key file into the composite-keyed v6 store`() {
        // Seed a v5 file + flat vault secrets, as the old single-account store wrote.
        Files.writeString(
            workDir / "credentials.json",
            """{"username":"ChaosA","uuid":"$scUuid","uid":"1","version":5}""",
        )
        vault.entries["accessToken"] = "fake-game-token".toByteArray()
        vault.entries["password"] = "secret-pw".toByteArray()

        val loaded = manager.load()
        assertNotNull(loaded)
        assertEquals("ChaosA", loaded.playerName)
        assertEquals("fake-game-token", loaded.accessToken)
        assertEquals("secret-pw", loaded.cachedPassword)

        // Re-keyed to composite, flat keys dropped, file stamped v6.
        assertEquals("fake-game-token", vault.entries[scKey("accessToken")]?.decodeToString())
        assertNull(vault.entries["accessToken"], "flat key removed after migration")
        assertEquals(6, fileJson()["version"]?.jsonPrimitive?.int)
        // A second load is pure v6 -- no re-migration.
        assertEquals("ChaosA", newManager().load()?.playerName)
    }

    @Test
    fun `v5 migration is idempotent when interrupted after the re-key`() {
        // Simulate a crash AFTER the composite re-key but BEFORE the file was stamped:
        // flat keys gone, composite present, file still v5.
        Files.writeString(
            workDir / "credentials.json",
            """{"username":"ChaosA","uuid":"$scUuid","uid":"1","version":5}""",
        )
        vault.entries[scKey("accessToken")] = "fake-game-token".toByteArray()

        val loaded = manager.load()
        assertNotNull(loaded)
        assertEquals("fake-game-token", loaded.accessToken)
        assertEquals(6, fileJson()["version"]?.jsonPrimitive?.int)
    }

    // ── migration: v4 legacy store -> v6 ────────────────────────────────────────

    @Test
    fun `migrates a v4 keyring-mode file into the v6 store and purges old entries`() {
        LegacyCredentialsManager(workDir, json, legacyKeyring).save(session())
        assertEquals(4, fileJson()["version"]?.jsonPrimitive?.int)

        val loaded = manager.load()
        assertNotNull(loaded)
        assertEquals("fake-game-token", loaded.accessToken)
        assertEquals("secret-pw", loaded.cachedPassword)

        assertEquals("fake-game-token", vault.entries[scKey("accessToken")]?.decodeToString())
        assertEquals(6, fileJson()["version"]?.jsonPrimitive?.int)
        assertTrue(legacyKeyring.entries.isEmpty(), "old keyring entries purged after migration")
        assertEquals("fake-game-token", newManager().load()?.accessToken)
    }

    @Test
    fun `legacy file with unrecoverable secrets returns null and stamps v6`() {
        LegacyCredentialsManager(workDir, json, legacyKeyring).save(session())
        legacyKeyring.entries.clear()

        assertNull(manager.load(), "no recoverable secret -> re-login")
        assertEquals(6, fileJson()["version"]?.jsonPrimitive?.int)
    }

    // ── Fakes ─────────────────────────────────────────────────────────────────

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

    @Test
    fun `the two-factor flag survives a save and reload`() {
        val mgr = newManager()
        mgr.saveAccount(
            SessionData(playerName = "tester", uuid = "u1", uid = "uid1", accessToken = "tok", twoFactor = true),
            "smartycraft",
        )
        // A property of the account, not of one sign-in: lose it on reload and the
        // launcher re-authenticates before a launch, which invalidates the session
        // the user unlocked with a code.
        assertTrue(mgr.accountFor("smartycraft")?.twoFactor == true, "flag must come back from disk")

        // A later save that does not know about it must not clear it.
        mgr.saveAccount(
            SessionData(playerName = "tester", uuid = "u1", uid = "uid1", accessToken = "tok2"),
            "smartycraft",
        )
        assertTrue(mgr.accountFor("smartycraft")?.twoFactor == true, "an ordinary re-save must not unset it")
    }

    @Test
    fun `clearTwoFactor releases the gate that an ordinary save cannot`() {
        val mgr = newManager()
        mgr.saveAccount(
            SessionData(playerName = "tester", uuid = "u1", uid = "uid1", accessToken = "tok", twoFactor = true),
            "smartycraft",
        )
        // Passing the flag as false is exactly what the prompt host used to do, and
        // it cannot work: saveAccount ORs the stored value back in on purpose.
        mgr.saveAccount(
            SessionData(playerName = "tester", uuid = "u1", uid = "uid1", accessToken = "tok", twoFactor = false),
            "smartycraft",
        )
        assertTrue(mgr.accountFor("smartycraft")?.twoFactor == true, "stickiness is deliberate")

        // Left stuck, the account fails every launch with TwoFactorExpired and
        // throws on every background sync pass, with no way back but removing and
        // re-adding it. The explicit release is the way out.
        mgr.clearTwoFactor("smartycraft")
        assertFalse(mgr.accountFor("smartycraft")?.twoFactor == true, "the explicit release must land")
    }

    @Test
    fun `clearTwoFactor leaves other providers alone`() {
        val mgr = newManager()
        mgr.saveAccount(
            SessionData(playerName = "tester", uuid = "u1", uid = "uid1", accessToken = "tok", twoFactor = true),
            "smartycraft",
        )
        mgr.saveAccount(
            SessionData(playerName = "msa", uuid = "u2", accessToken = "tok2", refreshToken = "r", twoFactor = true),
            "microsoft",
        )

        mgr.clearTwoFactor("smartycraft")

        assertFalse(mgr.accountFor("smartycraft")?.twoFactor == true)
        assertTrue(mgr.accountFor("microsoft")?.twoFactor == true, "another provider's gate is untouched")
    }
}
