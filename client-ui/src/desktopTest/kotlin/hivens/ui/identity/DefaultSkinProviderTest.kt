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
    private lateinit var libraries: Path
    private lateinit var cache: Path

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 13, 10, 26, 10)

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("nexira-defskin-")
        libraries = root / "libraries"
        cache = root / "cache"
    }

    @AfterTest
    fun teardown() {
        Files.walk(root).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
    }

    /** A provisioned client jar where the shared root actually keeps it. */
    private fun writeJar(mcVersion: String, entries: Map<String, ByteArray>) {
        val dir = libraries / "net" / "minecraft" / "minecraft" / mcVersion
        Files.createDirectories(dir)
        ZipOutputStream(Files.newOutputStream(dir / "minecraft-$mcVersion.jar")).use { zip ->
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
        writeJar("1.21.1", modernEntries())
        val skins = DefaultSkinProvider(libraries, cache).list()
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
            "1.12.2",
            mapOf(
                "assets/minecraft/textures/entity/steve.png" to png,
                "assets/minecraft/textures/entity/alex.png" to png,
            ),
        )
        val skins = DefaultSkinProvider(libraries, cache).list()
        assertEquals(listOf("Steve", "Alex"), skins.map { it.name })
    }

    @Test
    fun `empty when no client jar carries player textures`() {
        writeJar("1.21.1", mapOf("assets/minecraft/textures/block/stone.png" to png))
        assertTrue(DefaultSkinProvider(libraries, cache).list().isEmpty())
    }

    @Test
    fun `the newest client jar wins over an older one`() {
        // Both carry textures, and the legacy pair is the wrong answer when a
        // modern client is on disk: the reader gets two defaults instead of nine.
        writeJar(
            "1.12.2",
            mapOf(
                "assets/minecraft/textures/entity/steve.png" to png,
                "assets/minecraft/textures/entity/alex.png" to png,
            ),
        )
        writeJar("1.21.1", modernEntries())
        assertEquals(9, DefaultSkinProvider(libraries, cache).list().size)
    }

    /**
     * Somebody upgrading has the retired server path's tree and may not have
     * installed a pack yet. Dropping that root would have answered them with an
     * empty skin row for a reason they cannot act on.
     */
    @Test
    fun `the retired clients tree still answers when nothing is provisioned`() {
        val clients = root / "clients"
        val bin = clients / "SkyBlock" / "bin"
        Files.createDirectories(bin)
        ZipOutputStream(Files.newOutputStream(bin / "smartycraft-1.21.1.jar")).use { zip ->
            modernEntries().forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            }
        }
        assertEquals(9, DefaultSkinProvider(libraries, cache, clients).list().size)
    }

    @Test
    fun `a provisioned client wins over the retired tree`() {
        val clients = root / "clients"
        val bin = clients / "SkyBlock" / "bin"
        Files.createDirectories(bin)
        ZipOutputStream(Files.newOutputStream(bin / "smartycraft-1.12.2.jar")).use { zip ->
            zip.putNextEntry(ZipEntry("assets/minecraft/textures/entity/steve.png")); zip.write(png); zip.closeEntry()
        }
        writeJar("1.21.1", modernEntries())
        assertEquals(9, DefaultSkinProvider(libraries, cache, clients).list().size)
    }

    @Test
    fun `second call serves the cache after the jar is gone`() {
        writeJar("1.21.1", modernEntries())
        val p = DefaultSkinProvider(libraries, cache)
        assertEquals(9, p.list().size)
        Files.walk(libraries / "net").use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
        assertEquals(9, p.list().size, "cached defaults survive the source jar going away")
    }
}
