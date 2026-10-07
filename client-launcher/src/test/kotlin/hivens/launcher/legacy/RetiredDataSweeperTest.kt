package hivens.launcher.legacy

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the sweep takes and, more to the point, what it leaves.
 *
 * The bookkeeping files describe the SET of clients, so they go with the last
 * one and not the first. Sweeping three of seven has to leave the manifest cache
 * and the profiles alone, because the four still there are still described by
 * them -- and because a player who cleared some and kept others has said exactly
 * that.
 */
class RetiredDataSweeperTest {

    private lateinit var data: Path
    private lateinit var clients: Path

    @BeforeTest
    fun setup() {
        data = Files.createTempDirectory("nexira-retired-sweep-")
        clients = (data / "clients").also { it.createDirectories() }
        (data / "manifest-cache").createDirectories()
        (data / "manifest-cache" / "Industrial.json").writeText("{}")
        (data / "profiles.json").writeText("{}")
        (data / "servers-cache.json").writeText("{}")
        (data / "protected-paths.json").writeText("{}")
        // A file the retired path never owned, to prove the sweep is not a broom.
        (data / "settings.json").writeText("{}")
    }

    @AfterTest
    fun teardown() {
        Files.walk(data).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun client(name: String, bytes: Long = 100L): RetiredClient {
        val dir = (clients / name).also { it.createDirectories() }
        (dir / "mods").createDirectories()
        (dir / "mods" / "JEI.jar").writeText("x")
        (dir / "options.txt").writeText("y")
        return RetiredClient(name, dir, bytes, "1.12.2", "forge", 1)
    }

    @Test
    fun `sweeping every client takes the bookkeeping with it`() = runTest {
        val a = client("Industrial", 500L)
        val b = client("Galaxy", 300L)

        val swept = RetiredDataSweeper(data).sweep(listOf(a, b))

        assertContentEquals(listOf("Industrial", "Galaxy"), swept.clients)
        assertEquals(800L, swept.bytes)
        assertTrue(swept.leftoversRemoved)
        assertFalse((data / "clients").exists(), "the empty root goes too")
        assertFalse((data / "manifest-cache").exists())
        assertFalse((data / "profiles.json").exists())
        assertFalse((data / "servers-cache.json").exists())
        assertFalse((data / "protected-paths.json").exists())
        assertTrue((data / "settings.json").exists(), "the sweep touches only the retired path's own files")
    }

    @Test
    fun `sweeping some leaves the bookkeeping for the ones that remain`() = runTest {
        val a = client("Industrial")
        client("Galaxy")

        val swept = RetiredDataSweeper(data).sweep(listOf(a))

        assertContentEquals(listOf("Industrial"), swept.clients)
        assertFalse(swept.leftoversRemoved)
        assertFalse(a.dir.exists())
        assertTrue((clients / "Galaxy").exists())
        assertTrue((data / "manifest-cache").exists(), "it still describes the one left")
        assertTrue((data / "profiles.json").exists())
        assertTrue((data / "protected-paths.json").exists())
    }

    @Test
    fun `sweeping nothing does nothing and does not run the hook`() = runTest {
        var hookRan = false
        val swept = RetiredDataSweeper(data, beforeFirstDelete = { hookRan = true }).sweep(emptyList())

        assertEquals(emptyList(), swept.clients)
        assertFalse(swept.leftoversRemoved)
        assertFalse(hookRan, "nothing is about to be deleted, so nothing has to be rescued first")
        assertTrue((data / "clients").exists())
    }

    /**
     * The hook exists for the default skins: they are read out of a client jar,
     * and on an upgraded install `clients/` is the only place one lives until a
     * pack is installed. It has to run while the tree is still there.
     */
    @Test
    fun `the hook runs before the first deletion, with the tree still present`() = runTest {
        val a = client("Industrial")
        var treeWasThere = false

        RetiredDataSweeper(data, beforeFirstDelete = { treeWasThere = a.dir.exists() })
            .sweep(listOf(a))

        assertTrue(treeWasThere, "the hook must see what it is meant to rescue")
        assertFalse(a.dir.exists())
    }

    @Test
    fun `a client already gone is reported as swept rather than as a failure`() = runTest {
        val a = client("Industrial")
        Files.walk(a.dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }

        val swept = RetiredDataSweeper(data).sweep(listOf(a))
        assertContentEquals(listOf("Industrial"), swept.clients)
    }
}
