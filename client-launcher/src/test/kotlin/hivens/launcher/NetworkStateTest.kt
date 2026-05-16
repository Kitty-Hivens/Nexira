package hivens.launcher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkStateTest {

    private lateinit var workDir: Path
    private lateinit var bypassFile: Path

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("aura-netstate-test-")
        bypassFile = workDir / "ssl-bypasses.json"
        NetworkState.clearForTests()
        NetworkState.initialize(bypassFile)
    }

    @AfterTest
    fun teardown() {
        NetworkState.clearForTests()
        Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // ── bypass semantics ───────────────────────────────────────────────────

    @Test
    fun `bypassFor returns false when no grants exist`() {
        assertFalse(NetworkState.bypassFor("www.smartycraft.ru"))
    }

    @Test
    fun `bypassFor returns true after grant with future expiry`() {
        val future = Instant.now().plus(1, ChronoUnit.HOURS)
        NetworkState.grantBypass("www.smartycraft.ru", future)
        assertTrue(NetworkState.bypassFor("www.smartycraft.ru"))
    }

    @Test
    fun `bypassFor returns false after expiry passes`() {
        // Grant for 1 millisecond, then wait it out -- deterministic, no
        // sleeps longer than needed.
        val almostNow = Instant.now().plus(1, ChronoUnit.MILLIS)
        NetworkState.grantBypass("www.smartycraft.ru", almostNow)
        Thread.sleep(50)
        assertFalse(NetworkState.bypassFor("www.smartycraft.ru"))
    }

    @Test
    fun `bypassFor is host-scoped -- grant for one host doesn't affect another`() {
        val future = Instant.now().plus(1, ChronoUnit.HOURS)
        NetworkState.grantBypass("www.smartycraft.ru", future)
        assertTrue(NetworkState.bypassFor("www.smartycraft.ru"))
        assertFalse(NetworkState.bypassFor("github.com"))
        assertFalse(NetworkState.bypassFor("evil.example.com"))
    }

    @Test
    fun `grantBypass replaces existing entry for the same host (no duplicates)`() {
        val first = Instant.now().plus(1, ChronoUnit.HOURS)
        val second = Instant.now().plus(30, ChronoUnit.DAYS)
        NetworkState.grantBypass("www.smartycraft.ru", first)
        NetworkState.grantBypass("www.smartycraft.ru", second)
        val entries = NetworkState.listBypasses()
        assertEquals(1, entries.size, "second grant must replace, not add")
        // The new expiry should reflect the second call.
        assertTrue(Instant.parse(entries[0].expiresAt).isAfter(first))
    }

    @Test
    fun `revokeBypass removes existing entry`() {
        val future = Instant.now().plus(1, ChronoUnit.HOURS)
        NetworkState.grantBypass("www.smartycraft.ru", future)
        NetworkState.revokeBypass("www.smartycraft.ru")
        assertFalse(NetworkState.bypassFor("www.smartycraft.ru"))
        assertEquals(0, NetworkState.listBypasses().size)
    }

    @Test
    fun `revokeBypass is idempotent -- revoking absent entry doesn't throw`() {
        NetworkState.revokeBypass("never-existed.example.com")
        assertEquals(0, NetworkState.listBypasses().size)
    }

    // ── persistence ────────────────────────────────────────────────────────

    @Test
    fun `grant writes the JSON file`() {
        val future = Instant.now().plus(1, ChronoUnit.HOURS)
        NetworkState.grantBypass("www.smartycraft.ru", future)
        assertTrue(Files.exists(bypassFile), "grant must persist to disk")
        val text = Files.readString(bypassFile)
        assertTrue(text.contains("www.smartycraft.ru"))
        assertTrue(text.contains("expiresAt"))
    }

    @Test
    fun `initialize re-reads existing JSON file on launcher restart`() {
        val future = Instant.now().plus(1, ChronoUnit.HOURS)
        NetworkState.grantBypass("www.smartycraft.ru", future)
        // Simulate process restart: clear in-memory state, re-initialize
        // from the same file.
        NetworkState.clearForTests()
        NetworkState.initialize(bypassFile)
        assertTrue(NetworkState.bypassFor("www.smartycraft.ru"), "persisted bypass must survive restart")
    }

    @Test
    fun `expired entries are dropped during load -- no silent re-arm across restart`() {
        // Write an already-expired entry to disk directly, then initialize.
        // This simulates a 30-day grant from a month ago when the user
        // restarts the launcher today.
        val pastIso = Instant.now().minus(1, ChronoUnit.DAYS).toString()
        Files.writeString(
            bypassFile,
            """[{"host":"stale.example.com","expiresAt":"$pastIso"}]""",
        )
        NetworkState.clearForTests()
        NetworkState.initialize(bypassFile)
        assertFalse(NetworkState.bypassFor("stale.example.com"), "stale entry must not re-arm")
        assertEquals(0, NetworkState.listBypasses().size)
    }

    @Test
    fun `corrupt JSON file leaves bypass set empty -- no crash`() {
        Files.writeString(bypassFile, "{ not valid json at all")
        NetworkState.clearForTests()
        NetworkState.initialize(bypassFile)
        assertEquals(0, NetworkState.listBypasses().size)
        // bypassFor still works, just returns false everywhere.
        assertFalse(NetworkState.bypassFor("www.smartycraft.ru"))
    }

    @Test
    fun `revoke updates the persisted file too`() {
        val future = Instant.now().plus(1, ChronoUnit.HOURS)
        NetworkState.grantBypass("www.smartycraft.ru", future)
        NetworkState.revokeBypass("www.smartycraft.ru")
        // Re-initialize a fresh state instance and verify the file
        // reflects the revoke.
        NetworkState.clearForTests()
        NetworkState.initialize(bypassFile)
        assertFalse(NetworkState.bypassFor("www.smartycraft.ru"))
    }

    // ── multi-host coexistence ────────────────────────────────────────────

    @Test
    fun `multiple hosts coexist independently`() {
        val a = Instant.now().plus(1, ChronoUnit.HOURS)
        val b = Instant.now().plus(2, ChronoUnit.DAYS)
        NetworkState.grantBypass("www.smartycraft.ru", a)
        NetworkState.grantBypass("other.example.org", b)
        assertEquals(2, NetworkState.listBypasses().size)
        assertTrue(NetworkState.bypassFor("www.smartycraft.ru"))
        assertTrue(NetworkState.bypassFor("other.example.org"))
        NetworkState.revokeBypass("www.smartycraft.ru")
        assertFalse(NetworkState.bypassFor("www.smartycraft.ru"))
        assertTrue(NetworkState.bypassFor("other.example.org"), "revoke must not affect unrelated host")
    }
}
