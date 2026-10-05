package hivens.auth

import dev.hivens.libvault.SecretVault
import dev.hivens.libvault.VaultTier
import hivens.core.data.NewerBuildData
import hivens.core.data.ReadOnlyStore
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
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
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

    /**
     * The uid is the input to every signed action. In the file next to a player name
     * it was enough to sign spawn, two-factor and skin upload for the account, while
     * the token derived from it sat behind the keyring.
     */
    @Test
    fun `the uid goes into the vault and never into the file`() {
        manager.save(session())

        assertEquals("1", vault.entries[scKey("uid")]?.decodeToString())
        assertNull(firstAccount()["uid"]?.jsonPrimitive?.contentOrNull, "no uid in credentials.json")
        assertEquals("1", newManager().load()?.uid)
    }

    @Test
    fun `a file that still carries a uid has it moved into the vault on the first read`() {
        Files.writeString(
            workDir / "credentials.json",
            """{"version":6,"activeAccountId":"$scUuid","accounts":[""" +
                """{"providerId":"smartycraft","accountId":"$scUuid","username":"ChaosA","uuid":"$scUuid","uid":"legacy-uid"}]}""",
        )
        vault.entries[scKey("accessToken")] = "fake-game-token".toByteArray()

        assertEquals("legacy-uid", manager.load()?.uid)
        assertEquals("legacy-uid", vault.entries[scKey("uid")]?.decodeToString())
        assertNull(firstAccount()["uid"]?.jsonPrimitive?.contentOrNull, "the file no longer carries it")
        assertEquals("legacy-uid", newManager().load()?.uid, "and it still loads from the vault")
    }

    @Test
    fun `a vault that will not take the uid leaves it in the file`() {
        Files.writeString(
            workDir / "credentials.json",
            """{"version":6,"activeAccountId":"$scUuid","accounts":[""" +
                """{"providerId":"smartycraft","accountId":"$scUuid","username":"ChaosA","uuid":"$scUuid","uid":"legacy-uid"}]}""",
        )
        vault.entries[scKey("accessToken")] = "fake-game-token".toByteArray()
        vault.refuseStore = true

        assertEquals("legacy-uid", manager.load()?.uid)
        assertEquals("legacy-uid", firstAccount()["uid"]?.jsonPrimitive?.contentOrNull, "dropped from both, it signs nothing")
    }

    @Test
    fun `removing an account removes its uid`() {
        manager.save(session())
        manager.removeAccount("smartycraft", scUuid)
        assertNull(vault.entries[scKey("uid")])
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
        assertEquals("ChaosA", manager.loadSession("smartycraft", scUuid)?.playerName)
        assertEquals("MsGamer", manager.loadSession("microsoft", "msuuid")?.playerName)
    }

    @Test
    fun `accountFor resolves the session for each provider`() {
        manager.save(session())
        manager.saveAccount(session(uuid = "msuuid", playerName = "MsGamer", refreshToken = "RT"), "microsoft")
        assertEquals("ChaosA", manager.accountFor("smartycraft")?.playerName)
        assertEquals("MsGamer", manager.accountFor("microsoft")?.playerName)
        assertNull(manager.accountFor("offline"))
    }

    /**
     * A rotated refresh token has to be stored, because the old one stops working,
     * but storing it is not the user choosing that account. Auto-login can finish
     * after the user has signed in by hand, and it used to take the active slot.
     */
    @Test
    fun `a save that is not a choice of account leaves the active one alone`() {
        manager.save(session())
        manager.saveAccount(
            session(uuid = "msuuid", playerName = "MsGamer", refreshToken = "RT2", password = null),
            "microsoft",
            makeActive = false,
        )

        assertEquals(scUuid, manager.activeAccountId())
        assertEquals("RT2", vault.entries["microsoft:msuuid:refreshToken"]?.decodeToString())
    }

    @Test
    fun `the first account saved without being chosen still becomes active`() {
        manager.saveAccount(session(), "smartycraft", makeActive = false)

        assertEquals(scUuid, manager.activeAccountId(), "a store with one account has no other to front it")
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
    fun `a named provider outranks licence priority`() {
        manager.save(session())
        manager.saveAccount(session(uuid = "msuuid", playerName = "MsGamer", refreshToken = "RT", password = null), "microsoft")
        // Licence priority alone puts Microsoft in front; naming SmartyCraft is
        // the user overruling that, which is the whole point of the setting.
        assertEquals("ChaosA", manager.primarySession("smartycraft")?.playerName)
    }

    @Test
    fun `a named provider with no account falls back to priority`() {
        manager.save(session())
        // The choice survives the account it named being signed out, so it must
        // not strand the shell faceless when that happens.
        assertEquals("ChaosA", manager.primarySession("microsoft")?.playerName)
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
        manager.removeAccount("microsoft", "msuuid")

        assertEquals(1, manager.listAccounts().size)
        assertNull(vault.entries["microsoft:msuuid:accessToken"])
        assertEquals(scUuid, manager.activeAccountId())
        assertEquals("ChaosA", manager.load()?.playerName)
    }

    // ── concurrent writers ─────────────────────────────────────────────────────

    /**
     * Every mutation reads the whole list, changes one entry and writes the list
     * back, and one press of Play into the two-factor gate runs two of them side by
     * side. Unserialised, the slower one wrote back what it had read before the other
     * landed.
     */
    @Test
    fun `writers running at once do not lose each other's changes`() {
        val threads = 8
        val perThread = 25
        val start = CountDownLatch(1)
        val workers = (0 until threads).map { t ->
            thread {
                start.await()
                repeat(perThread) { i ->
                    manager.saveAccount(session(uuid = "u-$t-$i", playerName = "p-$t-$i"), "smartycraft", makeActive = false)
                }
            }
        }
        start.countDown()
        workers.forEach { it.join() }

        assertEquals(threads * perThread, manager.listAccounts().size)
    }

    @Test
    fun `a flag written beside a save is kept`() {
        manager.save(session())
        val start = CountDownLatch(1)
        val marker = thread { start.await(); repeat(50) { manager.markTwoFactor("smartycraft") } }
        val saver = thread {
            start.await()
            repeat(50) { i -> manager.saveAccount(session(uuid = "other-$i", playerName = "o-$i"), "microsoft", makeActive = false) }
        }
        start.countDown()
        marker.join()
        saver.join()

        assertTrue(manager.accountFor("smartycraft")?.twoFactor == true)
        assertEquals(51, manager.listAccounts().size)
    }

    @Test
    fun `the accounts file is owner-only and leaves no temp file behind`() {
        manager.save(session())

        assertFalse(Files.exists(workDir / "credentials.json.tmp"))
        if (!workDir.fileSystem.supportedFileAttributeViews().contains("posix")) return
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(workDir / "credentials.json"),
        )
    }

    // ── a file from a newer build ──────────────────────────────────────────────

    /**
     * A save stamped the current format over a file a newer build had written and
     * dropped what that build keeps and this one cannot represent. Every sibling
     * store already opens such a file read-only.
     */
    @Test
    fun `a file written by a newer build is read and never written back`() {
        NewerBuildData.reset()
        val newer = """{"version":7,"activeAccountId":"$scUuid","somethingNewer":{"kept":true},"accounts":[""" +
            """{"providerId":"smartycraft","accountId":"$scUuid","username":"ChaosA","uuid":"$scUuid"}]}"""
        Files.writeString(workDir / "credentials.json", newer)
        vault.entries[scKey("accessToken")] = "fake-game-token".toByteArray()

        assertEquals("ChaosA", manager.load()?.playerName)
        manager.saveAccount(session(uuid = "other", playerName = "Other"), "smartycraft")
        manager.markTwoFactor("smartycraft")
        manager.removeAccount("smartycraft", scUuid)

        assertEquals(newer, Files.readString(workDir / "credentials.json"), "not a byte of it rewritten")
        assertEquals(setOf(ReadOnlyStore.Accounts), NewerBuildData.affected())
        NewerBuildData.reset()
    }

    // ── refreshStored ─────────────────────────────────────────────────────────

    @Test
    fun `a sign-in the launch made brings the stored account up to its uid and token`() {
        manager.save(session())
        manager.saveAccount(session(uuid = "msuuid", playerName = "MsGamer", refreshToken = "RT", password = null), "microsoft")

        manager.refreshStored("smartycraft", session(accessToken = "fresh-token", password = null).copy(uid = "fresh-uid"))

        val stored = manager.accountFor("smartycraft")
        assertEquals("fresh-uid", stored?.uid)
        assertEquals("fresh-token", stored?.accessToken)
        assertEquals("secret-pw", stored?.cachedPassword, "a secret the fresh session lacks is not lost")
        assertEquals("msuuid", manager.activeAccountId(), "and the active account stays")
    }

    @Test
    fun `a sign-in the user chose not to remember stays unremembered`() {
        manager.refreshStored("smartycraft", session())

        assertFalse(Files.exists(workDir / "credentials.json"))
        assertTrue(vault.entries.isEmpty())
    }

    // ── one id under two providers ─────────────────────────────────────────────

    /** The id is the uuid, or the name without one, so two providers can resolve to the same. */
    private fun twoProvidersOneId() {
        manager.saveAccount(session(uuid = "shared", playerName = "Shared", accessToken = "sc-token", password = "sc-pw"), "smartycraft")
        manager.saveAccount(
            session(uuid = "shared", playerName = "Shared", accessToken = "ms-token", password = null, refreshToken = "ms-rt"),
            "microsoft",
        )
    }

    @Test
    fun `each provider's account reads its own secrets when the two share an id`() {
        twoProvidersOneId()

        assertEquals("sc-token", manager.accountFor("smartycraft")?.accessToken)
        assertEquals("ms-token", manager.accountFor("microsoft")?.accessToken)
        assertEquals("ms-rt", manager.accountFor("microsoft")?.refreshToken)
        assertNull(manager.accountFor("smartycraft")?.refreshToken, "another provider's refresh token")
    }

    @Test
    fun `removing one provider's account leaves the other's record and secrets`() {
        twoProvidersOneId()

        manager.removeAccount("microsoft", "shared")

        assertNull(vault.entries["microsoft:shared:accessToken"])
        assertNull(vault.entries["microsoft:shared:refreshToken"])
        assertEquals("sc-token", manager.accountFor("smartycraft")?.accessToken, "the other account is still there")
        manager.clear()
        assertTrue(vault.entries.isEmpty(), "nothing left in the vault that no record names")
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

    @Test
    fun `a keyring that is down leaves the legacy file for the next launch`() {
        LegacyCredentialsManager(workDir, json, legacyKeyring).save(session())
        // The secret is still in the store. The store is simply not answering, which
        // is what a locked Secret Service or a daemon that has not come up looks
        // like, and its retrieve returns null exactly as an empty one would.
        legacyKeyring.available = false

        assertNull(manager.load(), "nothing to hand back while the store is down")
        assertEquals(4, fileJson()["version"]?.jsonPrimitive?.int, "the old file must survive an outage")

        // And it is still there once the store comes back.
        legacyKeyring.available = true
        assertEquals("fake-game-token", newManager().load()?.accessToken)
        assertEquals(6, fileJson()["version"]?.jsonPrimitive?.int)
    }

    @Test
    fun `an envelope that does not decrypt leaves the legacy file alone`() {
        // File-fallback mode with a ciphertext this machine cannot read. The key is
        // derived from the OS user, the home path and the platform, so a moved home
        // reads exactly like corruption and comes back when the seed does.
        Files.writeString(
            workDir / "credentials.json",
            """{"username":"ChaosA","uuid":"$scUuid","uid":"1","keyringHasAccessToken":false,""" +
                """"encryptedAccessToken":"not-a-ciphertext","accessTokenIv":"not-an-iv","version":4}""",
        )

        assertNull(manager.load())
        assertEquals(4, fileJson()["version"]?.jsonPrimitive?.int, "an unreadable envelope is not an absent one")
    }

    @Test
    fun `a v5 file whose vault returns nothing is left for the next launch`() {
        // The v5 file is the only record of who the secrets belong to, and the flat
        // keys are dropped on the success path alone.
        Files.writeString(
            workDir / "credentials.json",
            """{"username":"ChaosA","uuid":"$scUuid","uid":"1","version":5}""",
        )

        assertNull(manager.load())
        assertEquals(5, fileJson()["version"]?.jsonPrimitive?.int)

        vault.entries["accessToken"] = "fake-game-token".toByteArray()
        assertEquals("ChaosA", newManager().load()?.playerName, "recoverable once the vault answers")
    }

    @Test
    fun `a failed migration is attempted once per run, not per read`() {
        LegacyCredentialsManager(workDir, json, legacyKeyring).save(session())
        legacyKeyring.available = false

        val mgr = newManager()
        repeat(5) { mgr.load(); mgr.listAccounts(); mgr.activeAccountId() }

        // Every read path runs through the migration, and three of them sit inside
        // composition, so retrying per read would be a Secret Service probe per frame.
        assertEquals(4, fileJson()["version"]?.jsonPrimitive?.int)
        assertTrue(mgr.listAccounts().isEmpty())
    }

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakeVault : SecretVault {
        val entries: MutableMap<String, ByteArray> = ConcurrentHashMap()
        override val tier: VaultTier = VaultTier.Memory
        override val backend: String = "fake (test)"

        /** A vault that answers but will not take anything, the way a locked keyring does. */
        var refuseStore: Boolean = false

        override fun store(key: String, secret: ByteArray): Boolean {
            if (refuseStore) return false
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

        /**
         * A store that is down rather than empty. Its retrieve returns null either
         * way, which is what the interface documents and what the migration has to
         * tell apart.
         */
        var available: Boolean = true

        private fun key(service: String, account: String) = "$service::$account"

        override fun isAvailable(): Boolean = available

        override fun store(service: String, account: String, secret: String): Boolean {
            if (failStore) return false
            entries[key(service, account)] = secret
            return true
        }

        override fun retrieve(service: String, account: String): String? =
            if (available) entries[key(service, account)] else null

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
    fun `markTwoFactor arms the gate without touching the secrets or the active account`() {
        val mgr = newManager()
        mgr.saveAccount(session(), "smartycraft")
        mgr.saveAccount(
            SessionData(playerName = "msa", uuid = "u2", accessToken = "tok2", refreshToken = "r"),
            "microsoft",
        )
        val activeBefore = mgr.activeAccountId()

        mgr.markTwoFactor("smartycraft")

        assertTrue(mgr.accountFor("smartycraft")?.twoFactor == true, "the gate must be armed")
        assertEquals(activeBefore, mgr.activeAccountId(), "marking a flag must not change the primary face")
        assertEquals("fake-game-token", vault.entries[scKey("accessToken")]?.decodeToString())
        assertEquals("secret-pw", vault.entries[scKey("password")]?.decodeToString())
        assertFalse(mgr.accountFor("microsoft")?.twoFactor == true, "another provider's gate is untouched")
    }

    @Test
    fun `markTwoFactor on a provider with no account writes nothing`() {
        val mgr = newManager()
        mgr.markTwoFactor("smartycraft")
        assertFalse(Files.exists(workDir / "credentials.json"), "an empty store must not be created by a flag")
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
