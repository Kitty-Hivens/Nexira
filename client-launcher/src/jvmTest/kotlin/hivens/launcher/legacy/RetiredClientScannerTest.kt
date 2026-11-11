package hivens.launcher.legacy

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the scanner can and cannot tell from a leftover client tree.
 *
 * The fixtures are the three real shapes on a live install: a 1.7.10 client
 * whose assets archive carries no version at all, a 1.12.2 one with a flat
 * per-version libraries root, and a modern one whose `bin/` and `lib/` are a
 * bundled JRE rather than game natives. The last is the case that matters most:
 * it is two gigabytes of somebody else's Java, and both reading it as a loader
 * signal and carrying it into an instance would be wrong.
 */
class RetiredClientScannerTest {

    private lateinit var root: Path
    private lateinit var clients: Path

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("nexira-retired-scan-")
        clients = (root / "clients").also { it.createDirectories() }
    }

    @AfterTest
    fun teardown() {
        Files.walk(root).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun file(path: Path, bytes: Int = 1) {
        path.parent.createDirectories()
        path.writeBytes(ByteArray(bytes))
    }

    /** A 1.12.2 client: version-scoped natives, a flat libraries root, Forge in it. */
    private fun legacyForge(name: String, bytes: Int = 16): Path {
        val dir = clients / name
        file(dir / "bin" / "natives-1.12.2" / "liblwjgl.so")
        file(dir / "assets-1.12.2.zip")
        file(dir / "libraries-1.12.2" / "forge-1.12.2-14.23.5.2860.jar")
        file(dir / "libraries-1.12.2" / "asm-all-5.2.jar")
        file(dir / "mods" / "JEI.jar", bytes)
        file(dir / "config" / "jei.cfg")
        return dir
    }

    @Test
    fun `a legacy client reports its version, loader and mods`() = runTest {
        legacyForge("Industrial")
        val found = RetiredClientScanner(clients).scan().single()

        assertEquals("Industrial", found.name)
        assertEquals("1.12.2", found.mcVersion)
        assertEquals("forge", found.loader)
        assertEquals(1, found.modCount)
        assertTrue(found.sizeBytes > 0, "a tree with files in it is not zero bytes")
        assertTrue(found.adoptable)
    }

    /**
     * The oldest shape ships `assets.zip` with no version in the name, so the
     * natives directory is the only thing that answers. It leads for that reason.
     */
    @Test
    fun `a client whose assets archive is unversioned still reports its version`() = runTest {
        val dir = clients / "SkyBlock"
        file(dir / "bin" / "natives-1.7.10" / "liblwjgl.so")
        file(dir / "assets.zip")
        file(dir / "mods" / "Botania.jar")

        val found = RetiredClientScanner(clients).scan().single()
        assertEquals("1.7.10", found.mcVersion)
    }

    /**
     * A bundled JRE is not a loader. `bin/` full of `java.exe` and `lib/` full of
     * `jrt-fs.jar` says nothing about how the game boots, and the mods do.
     */
    @Test
    fun `a bundled runtime is not read as a loader signal`() = runTest {
        val dir = clients / "Create"
        file(dir / "bin" / "natives-1.21.1" / "liblwjgl.so")
        file(dir / "bin" / "java.exe")
        file(dir / "lib" / "jrt-fs.jar")
        file(dir / "lib" / "jvm.cfg")
        file(dir / "mods" / "neoforge-21.1.42.jar")

        val found = RetiredClientScanner(clients).scan().single()
        assertEquals("1.21.1", found.mcVersion)
        assertEquals("neoforge", found.loader)
    }

    /**
     * The real disagreement, and the reason the answer is a suggestion.
     *
     * One live client carries a Fabric loader log from an attempt that failed
     * beside a Forge-only mod. A jar the game boots through outranks a log file,
     * so the jar wins -- but the surface still shows what was decided, because
     * the next client will disagree in some way this rule does not cover.
     */
    @Test
    fun `a loader jar outranks a leftover loader log`() = runTest {
        val dir = clients / "Ambiguous"
        file(dir / "bin" / "natives-1.21.1" / "liblwjgl.so")
        file(dir / "fabricloader.log")
        file(dir / "libraries-1.21.1" / "neoforge-21.1.42.jar")

        assertEquals("neoforge", RetiredClientScanner(clients).scan().single().loader)
    }

    /** NeoForge jars end in the same four letters as Forge ones, so it is asked first. */
    @Test
    fun `neoforge is not mistaken for forge`() = runTest {
        val dir = clients / "Modern"
        file(dir / "bin" / "natives-1.21.1" / "liblwjgl.so")
        file(dir / "libraries-1.21.1" / "neoforge-21.1.42.jar")

        assertEquals("neoforge", RetiredClientScanner(clients).scan().single().loader)
    }

    @Test
    fun `a tree that names no version is described but not adoptable`() = runTest {
        val dir = clients / "Mystery"
        file(dir / "mods" / "Something.jar")
        file(dir / "options.txt")

        val found = RetiredClientScanner(clients).scan().single()
        assertNull(found.mcVersion)
        assertFalse(found.adoptable, "adoption without a version would provision the wrong Minecraft")
        assertTrue(found.sizeBytes > 0, "it is still there and still takes space")
    }

    @Test
    fun `the cheap check agrees with the count and costs no walk`() = runTest {
        val scanner = RetiredClientScanner(clients)
        assertFalse(scanner.anyLeftBehind(), "an empty clients dir has nothing left behind")
        assertEquals(0, scanner.count())

        legacyForge("Industrial")
        legacyForge("Galaxy")
        assertTrue(scanner.anyLeftBehind())
        assertEquals(2, scanner.count())
        assertEquals(listOf("Galaxy", "Industrial"), scanner.scan().map { it.name })
    }

    @Test
    fun `a missing clients dir is nothing rather than a failure`() = runTest {
        val scanner = RetiredClientScanner(root / "no-such-dir")
        assertFalse(scanner.anyLeftBehind())
        assertEquals(0, scanner.count())
        assertEquals(emptyList(), scanner.scan())
    }

    /** A loose file beside the clients is not a client. */
    @Test
    fun `only directories count`() = runTest {
        legacyForge("Industrial")
        (clients / "stray.txt").writeText("x")

        assertEquals(listOf("Industrial"), RetiredClientScanner(clients).scan().map { it.name })
    }
}
