package hivens.launcher

import hivens.config.Storage
import hivens.core.data.InstanceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Backfill for #190 -- ProfileManager had zero direct coverage. Tests cover:
 *   - load/save round-trip preserves every field including optionalModsState
 *   - corrupt state.json falls back to empty without throwing
 *   - toggleFavorite is idempotent and atomic under contention (#190 race)
 *   - save() is atomic at the file-system level (#190 torn-write protection)
 */
class ProfileManagerTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private lateinit var workDir: Path

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("aura-profiles-test-")
    }

    @AfterTest
    fun teardown() {
        Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun profilesFile() = workDir / Storage.PROFILES_FILE

    // ── Round-trip ────────────────────────────────────────────────────────

    @Test
    fun `loadProfiles on a clean install has no entries`() {
        val pm = ProfileManager(workDir, json)
        assertTrue(pm.favoriteServers.isEmpty())
        assertNull(pm.lastServerId)
        // No file written until something is saved -- the user shouldn't see
        // a stub profiles.json appear out of nowhere on first launch.
        assertFalse(Files.exists(profilesFile()), "no save() means no file")
    }

    @Test
    fun `saveProfile round-trips every field including optionalModsState`() {
        val pm = ProfileManager(workDir, json)
        val profile = InstanceProfile(
            serverId = "Industrial",
            memoryMb = 8192,
            javaPath = "/opt/jdk21/bin/java",
            jvmArgs = "-XX:+UseG1GC -XX:MaxGCPauseMillis=200",
            windowWidth = 1920,
            windowHeight = 1080,
            fullScreen = true,
            autoConnect = false,
            optionalModsState = mutableMapOf("optifine" to true, "shaders" to false),
        )
        pm.saveProfile(profile)
        pm.lastServerId = "Industrial"
        pm.save()

        val reloaded = ProfileManager(workDir, json)
        val read = reloaded.getProfile("Industrial")
        assertEquals(8192, read.memoryMb)
        assertEquals("/opt/jdk21/bin/java", read.javaPath)
        assertEquals("-XX:+UseG1GC -XX:MaxGCPauseMillis=200", read.jvmArgs)
        assertEquals(1920, read.windowWidth)
        assertEquals(1080, read.windowHeight)
        assertEquals(true, read.fullScreen)
        assertEquals(false, read.autoConnect)
        assertEquals(mapOf("optifine" to true, "shaders" to false), read.optionalModsState)
        assertEquals("Industrial", reloaded.lastServerId)
    }

    @Test
    fun `corrupt profiles_json falls back to empty without throwing`() {
        // Half-truncated JSON -- the kind of thing a crash mid-write used to
        // leave on disk before the temp-file + atomic-move fix.
        Files.writeString(profilesFile(), """{"profiles":{"Industrial":{"serverId":"Industrial","mem""")
        val pm = ProfileManager(workDir, json)
        assertTrue(pm.favoriteServers.isEmpty())
        assertNull(pm.lastServerId)
    }

    // ── Favorites ─────────────────────────────────────────────────────────

    @Test
    fun `toggleFavorite is idempotent on double-toggle`() {
        val pm = ProfileManager(workDir, json)
        pm.toggleFavorite("Industrial")
        assertTrue("Industrial" in pm.favoriteServers)
        pm.toggleFavorite("Industrial")
        assertFalse("Industrial" in pm.favoriteServers)
    }

    @Test
    fun `parallel toggleFavorite calls on the same server net to a single state flip`() = runBlocking {
        // Pre-#190 fix this would be a TOCTOU race: both calls observe
        // contains=false, both call add(), then save races. With atomic
        // add() the net state for an even count of toggles is "absent",
        // for an odd count "present". Hard part is that the launcher
        // intentionally favors latest-write-wins on the favorites file --
        // we're verifying the in-memory set ends up consistent.
        val pm = ProfileManager(workDir, json)
        val toggleCount = 100  // even -> final state must be "not favorite"
        coroutineScope {
            (1..toggleCount).map { async(Dispatchers.IO) { pm.toggleFavorite("Industrial") } }.awaitAll()
        }
        assertFalse("Industrial" in pm.favoriteServers, "even number of toggles must end in absent")
    }

    @Test
    fun `parallel toggleFavorite on distinct servers preserves all updates`() = runBlocking {
        val pm = ProfileManager(workDir, json)
        val servers = (1..50).map { "Server$it" }
        coroutineScope {
            servers.map { s -> async(Dispatchers.IO) { pm.toggleFavorite(s) } }.awaitAll()
        }
        assertEquals(servers.toSet(), pm.favoriteServers,
            "every distinct toggle should land -- pre-#190 the save() race could lose entries")
    }

    // ── File-system atomicity ─────────────────────────────────────────────

    @Test
    fun `save uses temp-file then atomic move so a crash never leaves torn JSON`() {
        // Strong invariant: at no point during save() should profiles.json
        // exist with non-parseable contents. We verify by parsing the file
        // immediately after save returns -- and crucially also that the temp
        // file (profiles.json.tmp) is gone, indicating the move succeeded.
        val pm = ProfileManager(workDir, json)
        pm.saveProfile(InstanceProfile(serverId = "Industrial", memoryMb = 8192))

        val text = Files.readString(profilesFile())
        json.parseToJsonElement(text)  // must parse -- throws if torn

        assertFalse(
            Files.exists(workDir / "${Storage.PROFILES_FILE}.tmp"),
            "temp file must be moved away by save()",
        )
    }

    @Test
    fun `concurrent saves do not produce a torn profiles_json on disk`(): Unit = runBlocking {
        val pm = ProfileManager(workDir, json)
        coroutineScope {
            (1..50).map { i ->
                async(Dispatchers.IO) {
                    pm.saveProfile(InstanceProfile(serverId = "Server$i", memoryMb = i * 128))
                }
            }.awaitAll()
        }
        // Whatever the final write was, the on-disk JSON must be parseable.
        val text = Files.readString(profilesFile())
        json.parseToJsonElement(text)  // throws if torn
    }
}
