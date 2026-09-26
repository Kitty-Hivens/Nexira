package hivens.launcher.component

import hivens.launcher.runtime.MavenCoord
import hivens.launcher.runtime.loader.ResolvedLibrary
import hivens.launcher.runtime.loader.ResolvedRuntime
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class EarlyLoadingScreenTest {

    @TempDir lateinit var dir: Path

    private val fmlToml get() = dir.resolve("config").resolve("fml.toml")

    // ─── which value a launch enforces ──────────────────────────────────────

    @Test
    fun `an instance that has not chosen loses the screen on wayland and keeps the pack's config elsewhere`() {
        assertEquals(false, EarlyLoadingScreen.enforced(null, waylandSession = true))
        assertNull(EarlyLoadingScreen.enforced(null, waylandSession = false))
    }

    @Test
    fun `a choice is enforced on every session`() {
        for (wayland in listOf(true, false)) {
            assertEquals(true, EarlyLoadingScreen.enforced(true, wayland))
            assertEquals(false, EarlyLoadingScreen.enforced(false, wayland))
        }
    }

    @Test
    fun `what the toggle shows is what the launch will do`() {
        // Nobody chose: wayland decides, and elsewhere the pack's own config does.
        assertFalse(EarlyLoadingScreen.effective(null, packValue = true, waylandSession = true))
        assertFalse(EarlyLoadingScreen.effective(null, packValue = false, waylandSession = false))
        assertTrue(EarlyLoadingScreen.effective(null, packValue = null, waylandSession = false))
        // A choice beats both.
        assertTrue(EarlyLoadingScreen.effective(true, packValue = false, waylandSession = true))
        assertFalse(EarlyLoadingScreen.effective(false, packValue = true, waylandSession = false))
    }

    @Test
    fun `the pack's value is read from the top level only`() {
        assertNull(EarlyLoadingScreen.readConfig(dir))
        Files.createDirectories(fmlToml.parent)
        Files.write(fmlToml, listOf("#Should we control the window.", "earlyWindowControl = false # shipped off"))
        assertEquals(false, EarlyLoadingScreen.readConfig(dir))
        Files.write(fmlToml, listOf("maxThreads = -1", "[dependencyOverrides]", "earlyWindowControl = true"))
        assertNull(EarlyLoadingScreen.readConfig(dir))
    }

    // ─── which packs have a screen at all ───────────────────────────────────

    @Test
    fun `neoforge and forge from 1_13 have a screen, legacy forge and other loaders do not`() {
        assertTrue(EarlyLoadingScreen.appliesTo("neoforge", "1.21.1"))
        assertTrue(EarlyLoadingScreen.appliesTo("NeoForge", "26.1"))
        assertTrue(EarlyLoadingScreen.appliesTo("forge", "1.20.1"))
        assertTrue(EarlyLoadingScreen.appliesTo("forge", "1.13.2"))
        assertTrue(EarlyLoadingScreen.appliesTo("forge", "26.1"))
        assertFalse(EarlyLoadingScreen.appliesTo("forge", "1.12.2"))
        assertFalse(EarlyLoadingScreen.appliesTo("forge", "1.7.10"))
        assertFalse(EarlyLoadingScreen.appliesTo("forge", null))
        assertFalse(EarlyLoadingScreen.appliesTo("fabric", "1.21.1"))
        assertFalse(EarlyLoadingScreen.appliesTo("cleanroom", "1.12.2"))
        assertFalse(EarlyLoadingScreen.appliesTo(null, "1.21.1"))
    }

    private fun runtimeWith(vararg coords: String) = ResolvedRuntime(
        libraries = coords.map { ResolvedLibrary(MavenCoord.parse(it), Path.of("/libs/$it.jar")) },
        clientJar = Path.of("/libs/client.jar"),
        mainClass = "cpw.mods.bootstraplauncher.BootstrapLauncher",
        assetIndexId = "17",
    )

    @Test
    fun `the config is only touched where the runtime carries the screen library`() {
        assertTrue(EarlyLoadingScreen.configurableIn(runtimeWith("net.neoforged.fancymodloader:earlydisplay:4.0.44")))
        assertTrue(EarlyLoadingScreen.configurableIn(runtimeWith("net.minecraftforge:fmlearlydisplay:1.20.1-47.4.20")))
        assertFalse(EarlyLoadingScreen.configurableIn(runtimeWith("net.minecraftforge:forge:1.16.5-36.2.39")))
    }

    // ─── writing fml.toml ───────────────────────────────────────────────────

    @Test
    fun `a missing file is created holding the one key`() {
        assertTrue(EarlyLoadingScreen.writeConfig(dir, enabled = false))
        assertEquals(listOf("earlyWindowControl = false"), Files.readAllLines(fmlToml))
    }

    @Test
    fun `an existing key is replaced in place and nothing else moves`() {
        Files.createDirectories(fmlToml.parent)
        val before = listOf(
            "#Early window height",
            "earlyWindowHeight = 720",
            "#Should we control the window.",
            "earlyWindowControl = true",
            "maxThreads = -1",
        )
        Files.write(fmlToml, before)

        assertTrue(EarlyLoadingScreen.writeConfig(dir, enabled = false))
        assertEquals(before.map { if (it.startsWith("earlyWindowControl")) "earlyWindowControl = false" else it }, Files.readAllLines(fmlToml))
    }

    @Test
    fun `a file that already says so is left untouched`() {
        Files.createDirectories(fmlToml.parent)
        Files.write(fmlToml, listOf("versionCheck = false", "earlyWindowControl = false"))
        val mtime = Files.getLastModifiedTime(fmlToml)

        assertFalse(EarlyLoadingScreen.writeConfig(dir, enabled = false))
        assertEquals(mtime, Files.getLastModifiedTime(fmlToml))
    }

    /**
     * NightConfig writes a non-empty `dependencyOverrides` as a table. A key
     * appended after its header would belong to it, and the top-level value FML
     * reads would stay at its default.
     */
    @Test
    fun `a missing key goes above any table so it stays top level`() {
        Files.createDirectories(fmlToml.parent)
        Files.write(fmlToml, listOf("maxThreads = -1", "[dependencyOverrides]", "targetMod = [\"-dep1\"]"))

        EarlyLoadingScreen.writeConfig(dir, enabled = false)
        val lines = Files.readAllLines(fmlToml)
        assertEquals("earlyWindowControl = false", lines.first())
        assertEquals(1, lines.count { it.startsWith("earlyWindowControl") })
    }

    @Test
    fun `a key of the same name inside a table is not the one replaced`() {
        Files.createDirectories(fmlToml.parent)
        Files.write(fmlToml, listOf("[dependencyOverrides]", "earlyWindowControl = [\"+x\"]"))

        EarlyLoadingScreen.writeConfig(dir, enabled = true)
        assertEquals(
            listOf("earlyWindowControl = true", "[dependencyOverrides]", "earlyWindowControl = [\"+x\"]"),
            Files.readAllLines(fmlToml),
        )
    }

    @Test
    fun `turning it back on undoes an earlier off`() {
        EarlyLoadingScreen.writeConfig(dir, enabled = false)
        assertTrue(EarlyLoadingScreen.writeConfig(dir, enabled = true))
        assertEquals(listOf("earlyWindowControl = true"), Files.readAllLines(fmlToml))
    }
}
