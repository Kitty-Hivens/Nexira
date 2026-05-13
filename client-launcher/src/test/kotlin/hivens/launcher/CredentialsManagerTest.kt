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
 * Tests the keyring-primary + file-fallback refactor with two independent
 * sensitive fields (password + accessToken). Uses an in-memory
 * [FakeKeyring] instead of mockk so the tests read like state machines.
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

    private fun session(password: String? = "secret-pw", accessToken: String = "fake-game-token") = SessionData(
        playerName = "ChaosA",
        accessToken = accessToken,
        uuid = "550e8400e29b41d4a716446655440000",
        uid = "1",
        cachedPassword = password,
        status = null,
    )

    private fun fileJson(): JsonObject {
        val text = Files.readString(workDir / "credentials.json")
        return json.parseToJsonElement(text).jsonObject
    }

    private val passwordKey = "io.github.kitty_hivens.AuraLauncher::password"
    private val accessTokenKey = "io.github.kitty_hivens.AuraLauncher::accessToken"

    // ── save() ─────────────────────────────────────────────────────────────

    @Test
    fun `save with keyring available — both secrets land in keyring, file holds flags`() {
        manager.save(session())

        assertEquals("secret-pw", keyring.entries[passwordKey])
        assertEquals("fake-game-token", keyring.entries[accessTokenKey])

        val obj = fileJson()
        assertEquals(true, obj["keyringHasPassword"]?.jsonPrimitive?.boolean)
        assertEquals(true, obj["keyringHasAccessToken"]?.jsonPrimitive?.boolean)
        assertNull(obj["encryptedPassword"]?.jsonPrimitive?.contentOrNull)
        assertNull(obj["encryptedAccessToken"]?.jsonPrimitive?.contentOrNull)
        // Legacy plaintext accessToken field is never written in v4+.
        assertNull(obj["accessToken"]?.jsonPrimitive?.contentOrNull)
        assertEquals(4, obj["version"]?.jsonPrimitive?.contentOrNull?.toInt())
    }

    @Test
    fun `save with keyring failing — both secrets fall back to AES-GCM file`() {
        keyring.failStore = true
        manager.save(session())

        assertTrue(keyring.entries.isEmpty())

        val obj = fileJson()
        assertEquals(false, obj["keyringHasPassword"]?.jsonPrimitive?.boolean)
        assertEquals(false, obj["keyringHasAccessToken"]?.jsonPrimitive?.boolean)
        assertNotNull(obj["encryptedPassword"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(obj["passwordIv"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(obj["encryptedAccessToken"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(obj["accessTokenIv"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `save with blank accessToken — no-op, no file written`() {
        val noToken = session(accessToken = "")
        manager.save(noToken)
        assertFalse(Files.exists(workDir / "credentials.json"))
        assertTrue(keyring.entries.isEmpty())
    }

    @Test
    fun `save with null cachedPassword — password not stored, accessToken still goes to keyring`() {
        manager.save(session(password = null))

        assertNull(keyring.entries[passwordKey], "keyring must not store null password")
        assertEquals("fake-game-token", keyring.entries[accessTokenKey], "accessToken still gets stored")

        val obj = fileJson()
        assertEquals(false, obj["keyringHasPassword"]?.jsonPrimitive?.boolean)
        assertEquals(true, obj["keyringHasAccessToken"]?.jsonPrimitive?.boolean)
        assertNull(obj["encryptedPassword"]?.jsonPrimitive?.contentOrNull)
    }

    // ── load() ─────────────────────────────────────────────────────────────

    @Test
    fun `load v4 keyring-mode — both secrets come from keyring`() {
        manager.save(session())
        val freshManager = CredentialsManager(workDir, json, keyring)
        val loaded = freshManager.load()

        assertNotNull(loaded)
        assertEquals("ChaosA", loaded.playerName)
        assertEquals("secret-pw", loaded.cachedPassword)
        assertEquals("fake-game-token", loaded.accessToken)
    }

    @Test
    fun `load v4 keyring-mode but accessToken entry wiped — returns null (session gone)`() {
        manager.save(session())
        // Wipe only the accessToken keyring entry — daemon issue, manual
        // delete in seahorse, etc. Without accessToken the launcher cannot
        // launch the game; load returns null to trigger re-login.
        keyring.entries.remove(accessTokenKey)
        val freshManager = CredentialsManager(workDir, json, keyring)
        assertNull(freshManager.load(), "no accessToken = unusable session")
    }

    @Test
    fun `load v4 keyring-mode but password entry wiped — session loads with null cachedPassword`() {
        manager.save(session())
        // Wipe only the password entry. accessToken still works, so the
        // user can still launch the game; only password-dependent flows
        // (re-auth) need to prompt.
        keyring.entries.remove(passwordKey)
        val freshManager = CredentialsManager(workDir, json, keyring)
        val loaded = freshManager.load()

        assertNotNull(loaded, "accessToken survived — session is usable")
        assertEquals("fake-game-token", loaded.accessToken)
        assertNull(loaded.cachedPassword, "password is gone — relogin needed for re-auth")
    }

    @Test
    fun `load v4 file-mode — both secrets come from AES-GCM file`() {
        keyring.failStore = true
        manager.save(session())
        val freshManager = CredentialsManager(workDir, json, keyring)
        val loaded = freshManager.load()

        assertNotNull(loaded)
        assertEquals("secret-pw", loaded.cachedPassword)
        assertEquals("fake-game-token", loaded.accessToken)
    }

    @Test
    fun `load v3 legacy file with plaintext accessToken — migrates on next save`() {
        // Hand-craft a v3-format file: password in keyring (flag set, no
        // ciphertext) but accessToken still plaintext on disk. This is
        // exactly what a launcher upgraded from #139 → this PR would have
        // on disk for an already-logged-in user.
        val legacyFile = workDir / "credentials.json"
        keyring.entries[passwordKey] = "v3-password" // pretend keyring had it
        val legacyText = """
            {
                "username": "OldUser",
                "uuid": "00000000000000000000000000000000",
                "uid": "42",
                "keyringHasPassword": true,
                "accessToken": "v3-plaintext-token",
                "version": 3
            }
        """.trimIndent()
        Files.writeString(legacyFile, legacyText)

        // First load: reads v3 schema, surfaces the legacy plaintext token.
        val loaded = manager.load()
        assertNotNull(loaded)
        assertEquals("v3-plaintext-token", loaded.accessToken)
        assertEquals("v3-password", loaded.cachedPassword)

        // Save migrates accessToken into the keyring + bumps version.
        manager.save(loaded)
        assertEquals("v3-plaintext-token", keyring.entries[accessTokenKey])
        val obj = fileJson()
        assertEquals(4, obj["version"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals(true, obj["keyringHasAccessToken"]?.jsonPrimitive?.boolean)
        assertNull(obj["accessToken"]?.jsonPrimitive?.contentOrNull, "legacy plaintext field cleared")
    }

    @Test
    fun `load v1 legacy Base64 — migrates password on read, returns valid session`() {
        // v1 schema also had plaintext accessToken — same migration path
        // as v3 for the token; password comes from savedPasswordBase64.
        val legacyFile = workDir / "credentials.json"
        val legacyB64 = Base64.getEncoder().encodeToString("legacy-pw".toByteArray())
        val legacyText = """
            {
                "username": "OldUser",
                "accessToken": "v1-token",
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
        assertEquals("v1-token", loaded.accessToken)
    }

    @Test
    fun `load with no file — returns null`() {
        assertNull(manager.load())
    }

    @Test
    fun `load with malformed file — returns null instead of throwing`() {
        Files.writeString(workDir / "credentials.json", "not valid json {{{")
        assertNull(manager.load(), "load should swallow parse errors and return null")
    }

    // ── clear() ────────────────────────────────────────────────────────────

    @Test
    fun `clear wipes both keyring entries AND file`() {
        manager.save(session())
        assertEquals(2, keyring.entries.size, "save populated password + accessToken")

        manager.clear()

        assertFalse(Files.exists(workDir / "credentials.json"), "file must be deleted")
        assertTrue(keyring.entries.isEmpty(), "both keyring entries must be cleared")
    }

    @Test
    fun `clear is idempotent — calling twice does not throw`() {
        manager.save(session())
        manager.clear()
        manager.clear()
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
