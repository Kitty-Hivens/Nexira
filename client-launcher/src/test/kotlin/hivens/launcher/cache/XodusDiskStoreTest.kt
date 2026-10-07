package hivens.launcher.cache

import jetbrains.exodus.env.Environment
import jetbrains.exodus.env.Environments
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XodusDiskStoreTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var dir: Path
    private lateinit var env: Environment

    @BeforeTest
    fun open() {
        dir = Files.createTempDirectory("xodus")
        env = Environments.newInstance(dir.toFile())
    }

    @AfterTest
    fun close() {
        env.close()
        dir.toFile().deleteRecursively()
    }

    private fun strings(name: String = "s") = XodusDiskStore(env, name, String.serializer(), json)

    @Test
    fun `write then read round-trips value and timestamp`() {
        strings().write("k", "hello", 123L)
        val entry = strings().read("k")
        assertEquals("hello", entry?.value)
        assertEquals(123L, entry?.storedAtMillis)
    }

    /**
     * The failure this guards: a record widened in a release, read back from an
     * entry written before it, with every new field silently at its default. It
     * cost a project page a download count of zero for the seven days the stale
     * window allows, and nothing anywhere said why.
     */
    @Test
    fun `a record that gained a field does not read back from the old entry`() {
        XodusDiskStore(env, "widened", Narrow.serializer(), json).write("k", Narrow("sodium"), 1L)

        val widened = XodusDiskStore(env, "widened", Wide.serializer(), json).read("k")

        assertNull(widened, "an entry written before the field existed cannot answer for it")
    }

    @Test
    fun `the same record still reads back`() {
        XodusDiskStore(env, "same", Wide.serializer(), json).write("k", Wide("sodium", 7), 1L)
        assertEquals(7, XodusDiskStore(env, "same", Wide.serializer(), json).read("k")?.value?.downloads)
    }

    /**
     * A renamed wire field is a different record even though the types match, so
     * the fingerprint is over names and not shapes alone.
     */
    @Test
    fun `a renamed field is not the same record`() {
        XodusDiskStore(env, "renamed", Wide.serializer(), json).write("k", Wide("sodium", 7), 1L)
        assertNull(XodusDiskStore(env, "renamed", Renamed.serializer(), json).read("k"))
    }

    /**
     * The failure the first fingerprint had: every list carries the SAME serial
     * name whatever it holds, so a walk that remembered types globally expanded
     * the first list a record declared and collapsed every later one. Nothing
     * inside a second list was fingerprinted at all.
     */
    @Test
    fun `a field inside the second list still changes the record`() {
        XodusDiskStore(env, "lists", TwoLists.serializer(), json)
            .write("k", TwoLists(listOf("a"), listOf(Leaf("x"))), 1L)

        assertNull(
            XodusDiskStore(env, "lists", TwoListsWide.serializer(), json).read("k"),
            "a widened record nested in the second list must not read back",
        )
    }

    @Serializable
    private data class Leaf(val name: String)

    @Serializable
    private data class LeafWide(val name: String, val size: Long = 0)

    @Serializable
    private data class TwoLists(val tags: List<String>, val leaves: List<Leaf>)

    @Serializable
    private data class TwoListsWide(val tags: List<String>, val leaves: List<LeafWide>)

    @Serializable
    private data class Narrow(val slug: String)

    @Serializable
    private data class Wide(val slug: String, val downloads: Long = 0)

    @Serializable
    private data class Renamed(val slug: String, val installs: Long = 0)

    @Test
    fun `a missing key reads null`() {
        assertNull(strings().read("nope"))
    }

    @Test
    fun `delete removes the entry`() {
        strings().write("k", "v", 1L)
        strings().delete("k")
        assertNull(strings().read("k"))
    }

    @Test
    fun `clear empties the namespace`() {
        strings().write("a", "1", 1L)
        strings().write("b", "2", 1L)
        strings().clear()
        assertNull(strings().read("a"))
        assertNull(strings().read("b"))
    }

    @Test
    fun `a mistyped or corrupt entry self-heals to null and is dropped`() {
        strings("shared").write("k", "hello", 1L)
        // Reading a String-json payload as Int fails to decode -> tolerant null + delete.
        val asInt = XodusDiskStore(env, "shared", Int.serializer(), json)
        assertNull(asInt.read("k"))
        assertNull(strings("shared").read("k"))
    }
}
