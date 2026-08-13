package hivens.launcher.network

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

class JsonSslBypassStoreTest {

    private lateinit var workDir: Path
    private lateinit var bypassFile: Path
    private lateinit var store: JsonSslBypassStore

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("nexira-bypass-store-test-")
        bypassFile = workDir / "ssl-bypasses.json"
        store = JsonSslBypassStore(bypassFile)
    }

    @AfterTest
    fun teardown() {
        Files.walk(workDir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
    }

    /** A launcher restart, which is the only thing that used to re-read the file. */
    private fun reopened() = JsonSslBypassStore(bypassFile)

    // ── bypass semantics ───────────────────────────────────────────────────

    @Test
    fun `no grants means no host is bypassed`() {
        assertFalse(store.isBypassed("www.smartycraft.ru"))
    }

    @Test
    fun `a grant with a future expiry holds`() {
        store.grant("www.smartycraft.ru", Instant.now().plus(1, ChronoUnit.HOURS))
        assertTrue(store.isBypassed("www.smartycraft.ru"))
    }

    @Test
    fun `a grant stops holding once its expiry passes`() {
        // Granted for a millisecond and waited out -- deterministic, and no
        // sleep longer than it takes.
        store.grant("www.smartycraft.ru", Instant.now().plus(1, ChronoUnit.MILLIS))
        Thread.sleep(50)
        assertFalse(store.isBypassed("www.smartycraft.ru"))
    }

    @Test
    fun `a grant covers the host it names and no other`() {
        store.grant("www.smartycraft.ru", Instant.now().plus(1, ChronoUnit.HOURS))
        assertTrue(store.isBypassed("www.smartycraft.ru"))
        assertFalse(store.isBypassed("github.com"))
        assertFalse(store.isBypassed("evil.example.com"))
    }

    @Test
    fun `a second grant for the same host replaces the first`() {
        val first = Instant.now().plus(1, ChronoUnit.HOURS)
        store.grant("www.smartycraft.ru", first)
        store.grant("www.smartycraft.ru", Instant.now().plus(30, ChronoUnit.DAYS))
        val entries = store.bypasses.value
        assertEquals(1, entries.size, "second grant must replace, not add")
        assertTrue(Instant.parse(entries[0].expiresAt).isAfter(first))
    }

    @Test
    fun `revoke removes the grant`() {
        store.grant("www.smartycraft.ru", Instant.now().plus(1, ChronoUnit.HOURS))
        store.revoke("www.smartycraft.ru")
        assertFalse(store.isBypassed("www.smartycraft.ru"))
        assertEquals(0, store.bypasses.value.size)
    }

    @Test
    fun `revoking a host that was never granted does not throw`() {
        store.revoke("never-existed.example.com")
        assertEquals(0, store.bypasses.value.size)
    }

    @Test
    fun `the published list follows every grant and revoke`() {
        assertEquals(emptyList(), store.bypasses.value)
        store.grant("www.smartycraft.ru", Instant.now().plus(1, ChronoUnit.HOURS))
        assertEquals(listOf("www.smartycraft.ru"), store.bypasses.value.map { it.host })
        store.revoke("www.smartycraft.ru")
        assertEquals(emptyList(), store.bypasses.value)
    }

    // ── persistence ────────────────────────────────────────────────────────

    @Test
    fun `a grant is written to the file`() {
        store.grant("www.smartycraft.ru", Instant.now().plus(1, ChronoUnit.HOURS))
        assertTrue(Files.exists(bypassFile), "grant must persist to disk")
        val text = Files.readString(bypassFile)
        assertTrue(text.contains("www.smartycraft.ru"))
        assertTrue(text.contains("expiresAt"))
    }

    @Test
    fun `a grant survives a restart`() {
        store.grant("www.smartycraft.ru", Instant.now().plus(1, ChronoUnit.HOURS))
        assertTrue(reopened().isBypassed("www.smartycraft.ru"), "persisted bypass must survive restart")
    }

    @Test
    fun `an expired entry does not re-arm across a restart`() {
        // A 30-day grant from a month ago, as it would sit on disk when the
        // user starts the launcher today.
        val pastIso = Instant.now().minus(1, ChronoUnit.DAYS).toString()
        Files.writeString(bypassFile, """[{"host":"stale.example.com","expiresAt":"$pastIso"}]""")
        val reopened = reopened()
        assertFalse(reopened.isBypassed("stale.example.com"), "stale entry must not re-arm")
        assertEquals(0, reopened.bypasses.value.size)
    }

    @Test
    fun `a corrupt file leaves the set empty rather than failing to construct`() {
        Files.writeString(bypassFile, "{ not valid json at all")
        val reopened = reopened()
        assertEquals(0, reopened.bypasses.value.size)
        assertFalse(reopened.isBypassed("www.smartycraft.ru"))
    }

    @Test
    fun `one unreadable timestamp drops that entry and keeps the rest`() {
        val future = Instant.now().plus(1, ChronoUnit.HOURS).toString()
        Files.writeString(
            bypassFile,
            """[{"host":"broken.example.com","expiresAt":"not-a-timestamp"},{"host":"good.example.com","expiresAt":"$future"}]""",
        )
        val reopened = reopened()
        assertEquals(listOf("good.example.com"), reopened.bypasses.value.map { it.host })
    }

    @Test
    fun `a revoke reaches the file too`() {
        store.grant("www.smartycraft.ru", Instant.now().plus(1, ChronoUnit.HOURS))
        store.revoke("www.smartycraft.ru")
        assertFalse(reopened().isBypassed("www.smartycraft.ru"))
    }

    @Test
    fun `without a file the grants stay in memory`() {
        val memory = JsonSslBypassStore()
        memory.grant("www.smartycraft.ru", Instant.now().plus(1, ChronoUnit.HOURS))
        assertTrue(memory.isBypassed("www.smartycraft.ru"))
        assertFalse(Files.exists(bypassFile))
    }

    // ── multi-host coexistence ────────────────────────────────────────────

    @Test
    fun `hosts coexist independently`() {
        store.grant("www.smartycraft.ru", Instant.now().plus(1, ChronoUnit.HOURS))
        store.grant("other.example.org", Instant.now().plus(2, ChronoUnit.DAYS))
        assertEquals(2, store.bypasses.value.size)
        assertTrue(store.isBypassed("www.smartycraft.ru"))
        assertTrue(store.isBypassed("other.example.org"))
        store.revoke("www.smartycraft.ru")
        assertFalse(store.isBypassed("www.smartycraft.ru"))
        assertTrue(store.isBypassed("other.example.org"), "revoke must not affect an unrelated host")
    }
}
