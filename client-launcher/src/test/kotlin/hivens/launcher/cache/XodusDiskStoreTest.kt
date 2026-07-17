package hivens.launcher.cache

import jetbrains.exodus.env.Environment
import jetbrains.exodus.env.Environments
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
