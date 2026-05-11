package hivens.launcher.component

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NeoForgeVersionDetectorTest {

    private lateinit var sandbox: Path
    private val detector = NeoForgeVersionDetector()

    @BeforeTest
    fun setUp() {
        sandbox = Files.createTempDirectory("aura-nf-detect-test-")
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    @Test
    fun `detects all four version components from a realistic layout`() {
        installNeoForge(sandbox, mcVersion = "1.21.1", forgeVersion = "21.1.506", fmlVersion = "4.0.42", neoFormVersion = "20240808.144430")

        val args = detector.detect(sandbox, "1.21.1")

        assertEquals(
            NeoForgeVersionDetector.FmlArgs(
                neoForgeVersion = "21.1.506",
                fmlVersion = "4.0.42",
                mcVersion = "1.21.1",
                neoFormVersion = "20240808.144430",
            ),
            args,
        )
    }

    @Test
    fun `picks highest NeoForge version when multiple dirs exist`() {
        installNeoForge(sandbox, mcVersion = "1.21.1", forgeVersion = "21.1.505", fmlVersion = "4.0.42", neoFormVersion = "20240808.144430")
        installNeoForge(sandbox, mcVersion = "1.21.1", forgeVersion = "21.1.506", fmlVersion = "4.0.42", neoFormVersion = "20240808.144430")
        installNeoForge(sandbox, mcVersion = "1.21.1", forgeVersion = "21.1.42",  fmlVersion = "4.0.42", neoFormVersion = "20240808.144430")

        val args = detector.detect(sandbox, "1.21.1")

        assertEquals("21.1.506", args?.neoForgeVersion)
    }

    @Test
    fun `returns null when libraries dir is missing`() {
        val args = detector.detect(sandbox, "1.21.1")
        assertNull(args)
    }

    @Test
    fun `returns null when neoforge dir is empty`() {
        // libraries-1.21.1/net/neoforged/ exists but no neoforge/<ver>/ subdir
        sandbox.resolve("libraries-1.21.1/net/neoforged/fancymodloader/loader/4.0.42").createDirectories()

        val args = detector.detect(sandbox, "1.21.1")
        assertNull(args)
    }

    @Test
    fun `returns null when universal jar manifest lacks neoform section`() {
        val libRoot = sandbox.resolve("libraries-1.21.1").also { it.createDirectories() }
        libRoot.resolve("net/neoforged/fancymodloader/loader/4.0.42").createDirectories()
        val forgeDir = libRoot.resolve("net/neoforged/neoforge/21.1.506").apply { createDirectories() }

        // Universal jar exists but its manifest has no neoform section
        writeJarWithManifest(
            forgeDir.resolve("neoforge-21.1.506-universal.jar"),
            manifestContent = "Manifest-Version: 1.0\r\n\r\n",
        )

        val args = detector.detect(sandbox, "1.21.1")
        assertNull(args)
    }

    @Test
    fun `falls back to plain libraries dir when libraries-mcVersion is absent`() {
        installNeoForge(sandbox, mcVersion = "1.21.1", forgeVersion = "21.1.506", fmlVersion = "4.0.42", neoFormVersion = "20240808.144430", customLayout = false)

        val args = detector.detect(sandbox, "1.21.1")

        assertEquals("21.1.506", args?.neoForgeVersion)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun installNeoForge(
        root: Path,
        mcVersion: String,
        forgeVersion: String,
        fmlVersion: String,
        neoFormVersion: String,
        customLayout: Boolean = true,
    ) {
        val libRoot = root.resolve(if (customLayout) "libraries-$mcVersion" else "libraries")
            .also { it.createDirectories() }

        libRoot.resolve("net/neoforged/fancymodloader/loader/$fmlVersion").createDirectories()

        val forgeDir = libRoot.resolve("net/neoforged/neoforge/$forgeVersion").apply { createDirectories() }
        val universalJar = forgeDir.resolve("neoforge-$forgeVersion-universal.jar")

        // Match real NeoForge layout: a `net/neoforged/neoforge/versions/neoform/` named
        // section whose Implementation-Version is `{mcVersion}-{neoFormVersion}`.
        val manifest = buildString {
            append("Manifest-Version: 1.0\r\n")
            append("FML-System-Mods: neoforge\r\n")
            append("\r\n")
            append("Name: net/neoforged/neoforge/versions/neoform/\r\n")
            append("Implementation-Version: $mcVersion-$neoFormVersion\r\n")
            append("\r\n")
        }
        writeJarWithManifest(universalJar, manifest)
    }

    private fun writeJarWithManifest(jar: Path, manifestContent: String) {
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write(manifestContent.toByteArray())
            zip.closeEntry()
        }
    }
}
