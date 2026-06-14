package hivens.ui.notifications

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationArchiveStoreTest {

    private lateinit var dir: Path
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("nexira-archive-test-")
    }

    @AfterTest
    fun tearDown() {
        runCatching { dir.toFile().deleteRecursively() }
    }

    private fun entry(
        kind: Kind,
        title: String,
        sourceKey: String = "pack:Create",
        severity: Severity = Severity.Info,
    ) = PersistedNotification(
        sourceKey = sourceKey,
        sender = "Create",
        iconUrl = null,
        severity = severity,
        kind = kind,
        title = title,
        body = null,
        createdAtEpoch = 0L,
    )

    @Test
    fun `a non-progress record persists and reloads across instances`() = runTest {
        val file = dir.resolve("n.json")
        val store = NotificationArchiveStore(file, json, this)
        store.record(entry(Kind.OneShot, "Done"))
        advanceUntilIdle()

        val reloaded = NotificationArchiveStore(file, json, this)
        assertEquals(listOf("Done"), reloaded.log.value.map { it.title }, "archive survives a fresh instance")
    }

    @Test
    fun `consecutive progress for one source coalesces in place`() = runTest {
        val store = NotificationArchiveStore(dir.resolve("n.json"), json, this)
        repeat(10) { i -> store.record(entry(Kind.Progress, "p$i")) }

        assertEquals(1, store.log.value.size, "a run of progress ticks is one entry")
        assertEquals("p9", store.log.value.single().title, "latest tick wins")
    }

    @Test
    fun `a progress run then a terminal keeps both`() = runTest {
        val store = NotificationArchiveStore(dir.resolve("n.json"), json, this)
        repeat(5) { store.record(entry(Kind.Progress, "tick")) }
        store.record(entry(Kind.OneShot, "done"))
        advanceUntilIdle()

        assertEquals(listOf("done", "tick"), store.log.value.map { it.title })
    }

    @Test
    fun `archive is capped, newest first`() = runTest {
        val store = NotificationArchiveStore(dir.resolve("n.json"), json, this, cap = 3)
        repeat(5) { i -> store.record(entry(Kind.OneShot, "e$i", sourceKey = "k$i")) }
        advanceUntilIdle()

        assertEquals(3, store.log.value.size)
        assertEquals("e4", store.log.value.first().title, "oldest dropped at the cap")
    }

    @Test
    fun `glyph survives the persistence round-trip`() = runTest {
        val file = dir.resolve("n.json")
        val store = NotificationArchiveStore(file, json, this)
        store.record(entry(Kind.OneShot, "Update available").copy(glyph = NotifGlyph.Update))
        advanceUntilIdle()

        val reloaded = NotificationArchiveStore(file, json, this)
        assertEquals(
            NotifGlyph.Update,
            reloaded.log.value.single().glyph,
            "the glyph projection round-trips through the archive file",
        )
    }

    @Test
    fun `clear empties memory and disk`() = runTest {
        val file = dir.resolve("n.json")
        val store = NotificationArchiveStore(file, json, this)
        store.record(entry(Kind.OneShot, "Done"))
        advanceUntilIdle()

        store.clear()
        advanceUntilIdle()

        assertTrue(store.log.value.isEmpty())
        assertTrue(NotificationArchiveStore(file, json, this).log.value.isEmpty(), "cleared state persisted")
    }
}
