package hivens.launcher

import hivens.core.data.SessionData
import hivens.core.security.IKeyringStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
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
 * Tests the keyring-primary + file-fallback refactor.
 *
 * Uses an in-memory [FakeKeyring] instead of a mock so the test reads
 * like a state-machine: store/retrieve/clear operations behave like a
 * real keyring would, but with explicit toggles for "daemon went away"
 * scenarios. Simpler than mockk for this many cases.
 */
class CredentialsManagerTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private lateinit var workDir: Path
    private lateinit var keyring: FakeKeyring
    private lateinit var manager: CredentialsManager

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("aura-creds-test-")
        keyring = FakeKeyring()
        manager = CredentialsManager(workDir, json, keyring)
    }

    @AfterTest
    fun teardown() {
        Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun session(password: String? = "secret-pw") = SessionData(
        playerName = "ChaosA",
        accessToken = "fake-game-token",
        uuid = "550e8400e29b41d4a716446655440000",
        uid = "1",
        cachedPassword = password,
        status = null,
    )

    private fun fileJson(): JsonObject {
        val text = Files.readString(workDir / "credentials.json")
        return json.parseToJsonElement(text).jsonObject
    }

    // ── save() ─────────────────────────────────────────────────────────────

    @Test
    fun `save with keyring available — password lands in keyring, file holds flag`() {
        manager.save(session())

        // Keyring got the secret. Service/account match the constants in
        // CredentialsManager — if those rename, this test must too (which
        // is the point: pin the wire format).
        assertEquals("secret-pw", keyring.entries["io.github.kitty_hivens.AuraLauncher::password"])
        // File records the flag, no ciphertext.
        val obj = fileJson()
        assertEquals(true, obj["keyringHasPassword"]?.jsonPrimitive?.boolean)
        assertNull(obj["encryptedPassword"]?.jsonPrimitive?.contentOrNull)
        assertNull(obj["passwordIv"]?.jsonPrimitive?.contentOrNull)
        assertEquals(3, obj["version"]?.jsonPrimitive?.contentOrNull?.toInt())
    }

    @Test
    fun `save with keyring failing — falls back to AES-GCM file path`() {
        keyring.failStore = true
        manager.save(session())

        // Nothing in keyring (it refused).
        assertTrue(keyring.entries.isEmpty())
        // File has the ciphertext + iv, flag is false.
        val obj = fileJson()
        assertEquals(false, obj["keyringHasPassword"]?.jsonPrimitive?.boolean)
        assertNotNull(obj["encryptedPassword"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(obj["passwordIv"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `save with blank accessToken — no-op, no file written`() {
        val noToken = session().copy(accessToken = "")
        manager.save(noToken)
        assertFalse(Files.exists(workDir / "credentials.json"))
        assertTrue(keyring.entries.isEmpty())
    }

    @Test
    fun `save with null cachedPassword — keyring not touched, file path used`() {
        // SessionData allows null cachedPassword (e.g. user logged in via cached token).
        // Should not invoke keyring.store(null) — would be a contract violation.
        manager.save(session(password = null))
        assertTrue(keyring.entries.isEmpty(), "keyring must not store null password")
        // File still gets written for the metadata; encryptedPassword is null too.
        val obj = fileJson()
        assertEquals(false, obj["keyringHasPassword"]?.jsonPrimitive?.boolean)
        assertNull(obj["encryptedPassword"]?.jsonPrimitive?.contentOrNull)
    }

    // ── load() ─────────────────────────────────────────────────────────────

    @Test
    fun `load v3 keyring-mode — pulls password from keyring`() {
        manager.save(session())
        // Re-create manager to simulate process restart.
        val freshManager = CredentialsManager(workDir, json, keyring)
        val loaded = freshManager.load()

        assertNotNull(loaded)
        assertEquals("ChaosA", loaded.playerName)
        assertEquals("secret-pw", loaded.cachedPassword)
        assertEquals("fake-game-token", loaded.accessToken)
    }

    @Test
    fun `load v3 keyring-mode but keyring lookup returns null — session loads with null password`() {
        manager.save(session())
        // Wipe the keyring entry as if the daemon was reset between sessions.
        keyring.entries.clear()
        val freshManager = CredentialsManager(workDir, json, keyring)
        val loaded = freshManager.load()

        assertNotNull(loaded, "metadata must survive keyring wipe — only the password is gone")
        assertEquals("ChaosA", loaded.playerName)
        assertNull(loaded.cachedPassword, "password is gone — re-login required")
    }

    @Test
    fun `load v3 file-mode — pulls password from AES-GCM file`() {
        keyring.failStore = true
        manager.save(session())
        // Restart with same keyring (still failing) to confirm file path round-trip.
        val freshManager = CredentialsManager(workDir, json, keyring)
        val loaded = freshManager.load()

        assertNotNull(loaded)
        assertEquals("secret-pw", loaded.cachedPassword)
    }

    @Test
    fun `load v1 legacy Base64 — migrates on read, returns valid session`() {
        // Hand-craft a v1-format file as it would have existed before AES-GCM.
        val legacyFile = workDir / "credentials.json"
        val legacyB64 = Base64.getEncoder().encodeToString("legacy-pw".toByteArray())
        val legacyText = """
            {
                "username": "OldUser",
                "accessToken": "old-token",
                "uuid": "00000000000000000000000000000000",
                "uid": "42",
                "savedPasswordBase64": "$legacyB64",
                "version": 1
            }
        """.trimIndent()
        Files.writeString(legacyFile, legacyText)

        val loaded = manager.load()
        assertNotNull(loaded)
        assertEquals("OldUser", loaded.playerName)
        assertEquals("legacy-pw", loaded.cachedPassword)
    }

    @Test
    fun `load with no file — returns null`() {
        // Fresh state, no save() was called.
        assertNull(manager.load())
    }

    @Test
    fun `load with malformed file — returns null instead of throwing`() {
        Files.writeString(workDir / "credentials.json", "not valid json {{{")
        assertNull(manager.load(), "load should swallow parse errors and return null")
    }

    // ── clear() ────────────────────────────────────────────────────────────

    @Test
    fun `clear wipes keyring AND file — both must be gone`() {
        manager.save(session())
        assertTrue(Files.exists(workDir / "credentials.json"))
        assertTrue(keyring.entries.isNotEmpty())

        manager.clear()

        assertFalse(Files.exists(workDir / "credentials.json"), "file must be deleted")
        assertTrue(keyring.entries.isEmpty(), "keyring entry must be cleared")
    }

    @Test
    fun `clear is idempotent — calling twice does not throw`() {
        manager.save(session())
        manager.clear()
        manager.clear() // second call against empty state
        assertFalse(Files.exists(workDir / "credentials.json"))
    }

    @Test
    fun `clear when only file path is in use — still wipes both sides`() {
        keyring.failStore = true
        manager.save(session())
        manager.clear()

        assertFalse(Files.exists(workDir / "credentials.json"))
        assertTrue(keyring.entries.isEmpty())
    }

    // ── In-memory keyring fake ────────────────────────────────────────────

    /**
     * Simple in-memory IKeyringStorage that tracks entries by
     * "service::account" key. `failStore` toggle simulates a
     * keyring daemon that's reachable (isAvailable=true) but
     * refuses writes — covers the "user revoked permission" case.
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

        override fun retrieve(service: String, account: String): String? =
            entries[key(service, account)]

        override fun clear(service: String, account: String): Boolean =
            entries.remove(key(service, account)) != null
    }
}
