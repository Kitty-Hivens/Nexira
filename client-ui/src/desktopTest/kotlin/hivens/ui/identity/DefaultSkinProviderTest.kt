package hivens.ui.identity

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultSkinProviderTest {
    private lateinit var root: Path
    private lateinit var clients: Path
    private lateinit var cache: Path

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 13, 10, 26, 10)

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("nexira-defskin-")
        clients = root / "clients"
        cache = root / "cache"
    }

    @AfterTest
    fun teardown() {
        Files.walk(root).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
    }

    private fun writeJar(pack: String, name: String, entries: Map<String, ByteArray>) {
        val bin = clients / pack / "bin"
        Files.createDirectories(bin)
        ZipOutputStream(Files.newOutputStream(bin / name)).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    private fun modernEntries(): Map<String, ByteArray> =
        listOf("steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri")
            .associate { "assets/minecraft/textures/entity/player/wide/$it.png" to png }

    @Test
    fun `extracts the nine modern defaults from a client jar`() {
        writeJar("Create", "neoforge-1.21.1.jar", modernEntries())
        val skins = DefaultSkinProvider(clients, cache).list()
        assertEquals(
            listOf("Steve", "Alex", "Ari", "Efe", "Kai", "Makena", "Noor", "Sunny", "Zuri"),
            skins.map { it.name },
        )
        assertTrue(skins.all { it.file.exists() })
        assertTrue((cache / "steve.png").exists())
        assertTrue(skins.first { it.name == "Alex" }.slim, "Alex is canonically slim")
        assertTrue(!skins.first { it.name == "Steve" }.slim, "Steve is canonically classic")
    }

    @Test
    fun `falls back to legacy steve and alex when no modern jar exists`() {
        writeJar(
            "Galaxy", "smartycraft-1.12.2.jar",
            mapOf(
                "assets/minecraft/textures/entity/steve.png" to png,
                "assets/minecraft/textures/entity/alex.png" to png,
            ),
        )
        val skins = DefaultSkinProvider(clients, cache).list()
        assertEquals(listOf("Steve", "Alex"), skins.map { it.name })
    }

    @Test
    fun `empty when no client jar carries player textures`() {
        writeJar("Empty", "x.jar", mapOf("assets/minecraft/textures/block/stone.png" to png))
        assertTrue(DefaultSkinProvider(clients, cache).list().isEmpty())
    }

    @Test
    fun `second call serves the cache after the jar is gone`() {
        writeJar("Create", "neoforge-1.21.1.jar", modernEntries())
        val p = DefaultSkinProvider(clients, cache)
        assertEquals(9, p.list().size)
        Files.walk(clients / "Create").use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
        assertEquals(9, p.list().size, "cached defaults survive the source jar going away")
    }
}
