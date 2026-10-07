package hivens.launcher.component

import hivens.launcher.runtime.MavenCoord
import hivens.launcher.runtime.loader.ResolvedLibrary
import hivens.launcher.runtime.loader.ResolvedRuntime
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
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
    fun `neoforge and forge where it has a screen, not legacy forge or other loaders`() {
        assertTrue(EarlyLoadingScreen.appliesTo("neoforge", "1.21.1"))
        assertTrue(EarlyLoadingScreen.appliesTo("NeoForge", "26.1"))
        assertTrue(EarlyLoadingScreen.appliesTo("forge", "1.20.1"))
        assertTrue(EarlyLoadingScreen.appliesTo("forge", "1.13.2"))
        assertTrue(EarlyLoadingScreen.appliesTo("forge", "1.16.5"))
        assertTrue(EarlyLoadingScreen.appliesTo("forge", "26.1"))
        // 1.17 to 1.19 drew their progress inside the game window.
        assertFalse(EarlyLoadingScreen.appliesTo("forge", "1.18.2"))
        assertFalse(EarlyLoadingScreen.appliesTo("forge", "1.19.4"))
        assertFalse(EarlyLoadingScreen.appliesTo("forge", "1.12.2"))
        assertFalse(EarlyLoadingScreen.appliesTo("forge", "1.7.10"))
        assertFalse(EarlyLoadingScreen.appliesTo("forge", null))
        assertFalse(EarlyLoadingScreen.appliesTo("fabric", "1.21.1"))
        assertFalse(EarlyLoadingScreen.appliesTo("cleanroom", "1.12.2"))
        assertFalse(EarlyLoadingScreen.appliesTo(null, "1.21.1"))
    }

    private fun runtimeWith(vararg coords: String) = ResolvedRuntime(
        libraries = coords.map { ResolvedLibrary(MavenCoord.parse(it), Path.of("/libs/${it.replace(':', '/')}.jar")) },
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

    /** The same key to TOML. Missing it put a second definition above, which the parser refuses. */
    @Test
    fun `a quoted key is the key`() {
        Files.createDirectories(fmlToml.parent)
        Files.write(fmlToml, listOf("\"earlyWindowControl\" = true", "maxThreads = -1"))

        assertTrue(EarlyLoadingScreen.writeConfig(dir, enabled = false))
        assertEquals(listOf("\"earlyWindowControl\" = false", "maxThreads = -1"), Files.readAllLines(fmlToml))
        assertEquals(false, EarlyLoadingScreen.readConfig(dir))
    }

    /** A pack written on Windows. One changed value must stay one changed value. */
    @Test
    fun `line endings, a byte-order mark, indent and a trailing comment all survive`() {
        Files.createDirectories(fmlToml.parent)
        Files.writeString(fmlToml, "﻿#Early window\r\n  earlyWindowControl = true # shipped on\r\nmaxThreads = -1\r\n")

        assertTrue(EarlyLoadingScreen.writeConfig(dir, enabled = false))
        assertEquals(
            "﻿#Early window\r\n  earlyWindowControl = false # shipped on\r\nmaxThreads = -1\r\n",
            Files.readString(fmlToml),
        )
        assertFalse(EarlyLoadingScreen.writeConfig(dir, enabled = false), "an unchanged value is not a change")
    }

    @Test
    fun `a missing key goes after a byte-order mark, not before it`() {
        Files.createDirectories(fmlToml.parent)
        Files.writeString(fmlToml, "﻿maxThreads = -1\n")

        EarlyLoadingScreen.writeConfig(dir, enabled = false)
        assertEquals("﻿earlyWindowControl = false\nmaxThreads = -1\n", Files.readString(fmlToml))
    }

    /** A config shared between instances through a link stays shared. */
    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "creating a symbolic link needs a privilege the runner does not have")
    fun `a linked config is written through the link`() {
        val shared = Files.createDirectories(dir.resolve("shared")).resolve("fml.toml")
        Files.write(shared, listOf("earlyWindowControl = true"))
        Files.createDirectories(fmlToml.parent)
        Files.createSymbolicLink(fmlToml, shared)

        EarlyLoadingScreen.writeConfig(dir, enabled = false)
        assertTrue(Files.isSymbolicLink(fmlToml), "the link was replaced by a file")
        assertEquals(listOf("earlyWindowControl = false"), Files.readAllLines(shared))
    }

    // ─── set for a launch, put back after it ────────────────────────────────

    /** The pack's file, byte for byte, between sessions: what its hash was taken of. */
    @Test
    fun `after the game the pack's file is back exactly as it shipped`() {
        Files.createDirectories(fmlToml.parent)
        val shipped = "#Should we control the window.\r\nearlyWindowControl = true\r\nmaxThreads = -1\r\n"
        Files.writeString(fmlToml, shipped)

        assertTrue(EarlyLoadingScreen.prepare(dir, enabled = false))
        assertEquals(false, EarlyLoadingScreen.readConfig(dir))

        assertTrue(EarlyLoadingScreen.restore(dir))
        assertEquals(shipped, Files.readString(fmlToml))
        assertFalse(EarlyLoadingScreen.restore(dir), "a second restore has nothing to do")
    }

    @Test
    fun `a file the pack never shipped is gone again after the game`() {
        EarlyLoadingScreen.prepare(dir, enabled = false)
        assertTrue(Files.exists(fmlToml))

        EarlyLoadingScreen.restore(dir)
        assertFalse(Files.exists(fmlToml))
    }

    /** Whoever changed it during the session meant to. */
    @Test
    fun `a value changed during the session is left as it is`() {
        Files.createDirectories(fmlToml.parent)
        Files.write(fmlToml, listOf("earlyWindowControl = true"))
        EarlyLoadingScreen.prepare(dir, enabled = false)
        Files.write(fmlToml, listOf("earlyWindowControl = true", "maxThreads = 4"))

        assertFalse(EarlyLoadingScreen.restore(dir))
        assertEquals(listOf("earlyWindowControl = true", "maxThreads = 4"), Files.readAllLines(fmlToml))
    }

    /** The launcher closed before the game: the next launch keeps the pack's copy, not its own. */
    @Test
    fun `a session that never restored is put back before the next one`() {
        Files.createDirectories(fmlToml.parent)
        Files.write(fmlToml, listOf("earlyWindowControl = true"))
        EarlyLoadingScreen.prepare(dir, enabled = false)

        EarlyLoadingScreen.prepare(dir, enabled = false)
        EarlyLoadingScreen.restore(dir)

        assertEquals(listOf("earlyWindowControl = true"), Files.readAllLines(fmlToml))
    }

    @Test
    fun `restoreAll puts back every instance left changed`() {
        val a = dir.resolve("a").also { Files.createDirectories(it.resolve("config")) }
        val b = dir.resolve("b").also { Files.createDirectories(it.resolve("config")) }
        Files.write(a.resolve("config/fml.toml"), listOf("earlyWindowControl = true"))
        Files.write(b.resolve("config/fml.toml"), listOf("earlyWindowControl = true"))
        EarlyLoadingScreen.prepare(a, enabled = false)

        assertEquals(1, EarlyLoadingScreen.restoreAll(dir))
        assertEquals(true, EarlyLoadingScreen.readConfig(a))
        assertEquals(true, EarlyLoadingScreen.readConfig(b))
    }

    @Test
    fun `nothing to change leaves nothing to restore`() {
        Files.createDirectories(fmlToml.parent)
        Files.write(fmlToml, listOf("earlyWindowControl = false"))

        assertFalse(EarlyLoadingScreen.prepare(dir, enabled = false))
        assertFalse(EarlyLoadingScreen.restore(dir))
    }

    @Test
    fun `turning it back on undoes an earlier off`() {
        EarlyLoadingScreen.writeConfig(dir, enabled = false)
        assertTrue(EarlyLoadingScreen.writeConfig(dir, enabled = true))
        assertEquals(listOf("earlyWindowControl = true"), Files.readAllLines(fmlToml))
    }
}
