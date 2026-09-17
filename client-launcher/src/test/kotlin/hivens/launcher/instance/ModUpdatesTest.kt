package hivens.launcher.instance

import hivens.core.api.dto.modrinth.ModrinthFile
import hivens.core.api.dto.modrinth.ModrinthHashes
import hivens.core.api.dto.modrinth.ModrinthVersion
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * What counts as an update, and what the replace does to the folder.
 *
 * Both halves have a wrong answer that looks like success: an endpoint that
 * answers "the newest version matching this instance" hands a current file back
 * to itself, and a replace that deletes before it installs loses a mod whenever
 * the install is the half that fails.
 */
class ModUpdatesTest {

    @TempDir lateinit var dir: Path

    private fun version(
        number: String,
        published: String,
        sha1: String,
        fileName: String = "mod-$number.jar",
        type: String = "release",
    ) = ModrinthVersion(
        id            = "v-$number",
        projectId     = "proj",
        name          = number,
        versionNumber = number,
        versionType   = type,
        gameVersions  = listOf("1.21.1"),
        loaders       = listOf("neoforge"),
        datePublished = published,
        files         = listOf(
            ModrinthFile(hashes = ModrinthHashes(sha1), url = "https://cdn/$fileName", filename = fileName, primary = true, size = 10),
        ),
    )

    private val ref = ContentRef(ContentKind.Mod, "mod-1.0.jar")

    // ── what the answer means ────────────────────────────────────────────────

    @Test
    fun `an answer that is the installed file is not an update`() {
        val current = version("1.0", "2026-01-01", sha1 = "aaaa")
        assertNull(
            updateFrom(ref, installedSha1 = "aaaa", installedVersion = "1.0", candidates = listOf(current)),
            "the endpoint answers with the newest MATCHING version, which for a current file is itself",
        )
    }

    @Test
    fun `a differing hash is the update, and it carries what to download`() {
        val next = version("1.1", "2026-02-01", sha1 = "bbbb", fileName = "mod-1.1.jar")
        val update = updateFrom(ref, "aaaa", "1.0", listOf(next))
        assertNotNull(update)
        update!!
        assertEquals("v-1.1", update.versionId)
        assertEquals("1.1", update.versionNumber)
        assertEquals("mod-1.1.jar", update.fileName)
        assertEquals("bbbb", update.sha1)
        assertEquals("1.0", update.installedVersion, "the row still has to say what it is moving from")
        assertEquals(ref, update.ref)
    }

    @Test
    fun `hash comparison ignores case`() {
        val current = version("1.0", "2026-01-01", sha1 = "AAAA")
        assertNull(updateFrom(ref, "aaaa", "1.0", listOf(current)))
    }

    @Test
    fun `the newest by publish date wins, whatever order they arrive in`() {
        val older = version("1.1", "2026-02-01", sha1 = "bbbb")
        val newer = version("1.2", "2026-03-01", sha1 = "cccc")
        val update = updateFrom(ref, "aaaa", "1.0", listOf(newer, older))!!
        assertEquals("1.2", update.versionNumber)
        val reordered = updateFrom(ref, "aaaa", "1.0", listOf(older, newer))!!
        assertEquals("1.2", reordered.versionNumber, "list order is not part of the API contract")
    }

    @Test
    fun `a version carrying no file is skipped rather than crashing the check`() {
        val empty = ModrinthVersion(
            id = "v-broken", projectId = "proj", name = "broken", versionNumber = "1.3",
            datePublished = "2026-04-01", files = emptyList(),
        )
        val good = version("1.1", "2026-02-01", sha1 = "bbbb")
        val update = updateFrom(ref, "aaaa", "1.0", listOf(empty, good))!!
        assertEquals("1.1", update.versionNumber, "one malformed project must not cost the others their answer")
        assertNull(updateFrom(ref, "aaaa", "1.0", listOf(empty)))
    }

    @Test
    fun `nothing offered means nothing to do`() {
        assertNull(updateFrom(ref, "aaaa", "1.0", emptyList()))
    }

    // ── which loaders each folder is asked about ─────────────────────────────

    @Test
    fun `each folder is asked about the loaders its content is published for`() {
        assertEquals(listOf("neoforge"), loadersFor(ContentKind.Mod, "neoforge"))
        assertEquals(listOf("minecraft"), loadersFor(ContentKind.ResourcePack, "neoforge"))
        assertEquals(listOf("iris", "optifine"), loadersFor(ContentKind.ShaderPack, "neoforge"))
    }

    @Test
    fun `a vanilla instance has no loader to ask a mod about`() {
        assertTrue(loadersFor(ContentKind.Mod, "").isEmpty())
        assertEquals(listOf("minecraft"), loadersFor(ContentKind.ResourcePack, ""), "a resource pack is published for the game, not for a loader")
    }

    // ── the channel ladder ───────────────────────────────────────────────────

    @Test
    fun `the release ladder widens one rung at a time`() {
        assertEquals(listOf(listOf("release"), listOf("beta"), listOf("alpha")), ModUpdateChannel.Release.rungs)
        assertEquals(listOf(listOf("release", "beta"), listOf("alpha")), ModUpdateChannel.Beta.rungs)
        assertEquals(listOf(listOf("release", "beta", "alpha")), ModUpdateChannel.Alpha.rungs)
    }

    // ── the swap on disk ─────────────────────────────────────────────────────

    private fun mods(): Path = dir.resolve("mods").also { Files.createDirectories(it) }

    @Test
    fun `replacing installs the new file and retires the old one`() = runBlocking {
        val folder = mods()
        Files.writeString(folder.resolve("mod-1.0.jar"), "old")
        val scratch = Files.writeString(folder.resolve(".part"), "new")

        val ok = InstanceContentManager().replace(
            instanceDir = dir, kind = ContentKind.Mod,
            oldFileName = "mod-1.0.jar", source = scratch, newFileName = "mod-1.1.jar", enabled = true,
        )

        assertTrue(ok)
        assertFalse(Files.exists(folder.resolve("mod-1.0.jar")), "the replaced jar must not stay beside its replacement")
        assertEquals("new", Files.readString(folder.resolve("mod-1.1.jar")))
        assertFalse(Files.exists(scratch), "the scratch copy was moved, not left behind")
    }

    @Test
    fun `a disabled item stays disabled after an update`() = runBlocking {
        val folder = mods()
        Files.writeString(folder.resolve("mod-1.0.jar.disabled"), "old")
        val scratch = Files.writeString(folder.resolve(".part"), "new")

        InstanceContentManager().replace(
            instanceDir = dir, kind = ContentKind.Mod,
            oldFileName = "mod-1.0.jar", source = scratch, newFileName = "mod-1.1.jar", enabled = false,
        )

        assertTrue(Files.exists(folder.resolve("mod-1.1.jar.disabled")), "a mod turned off was not asked to come back on")
        assertFalse(Files.exists(folder.resolve("mod-1.1.jar")))
        assertFalse(Files.exists(folder.resolve("mod-1.0.jar.disabled")))
    }

    @Test
    fun `an update that keeps the file name still replaces its contents`() = runBlocking {
        val folder = mods()
        Files.writeString(folder.resolve("mod.jar"), "old")
        val scratch = Files.writeString(folder.resolve(".part"), "new")

        val ok = InstanceContentManager().replace(
            instanceDir = dir, kind = ContentKind.Mod,
            oldFileName = "mod.jar", source = scratch, newFileName = "mod.jar", enabled = true,
        )

        assertTrue(ok)
        assertEquals("new", Files.readString(folder.resolve("mod.jar")), "same name, new bytes -- and the delete must not run after the move")
    }
}
