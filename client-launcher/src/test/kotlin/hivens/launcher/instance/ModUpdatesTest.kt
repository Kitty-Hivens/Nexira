package hivens.launcher.instance

import hivens.core.api.dto.modrinth.ModrinthDependency
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

    // ── an older answer is not an update ─────────────────────────────────────

    @Test
    fun `a release older than the installed beta is not an update`() {
        // Exactly the Iris case: 1.8.14-beta.1 installed, and the release channel
        // answers with 1.8.12 from a year earlier. Different hash, older build.
        val older = version("1.8.12", "2025-06-17", sha1 = "bbbb")
        assertNull(
            updateFrom(ref, "aaaa", "1.8.14-beta.1", listOf(older), installedPublishedAt = "2026-06-13"),
            "asking the release channel about a beta must not offer a rollback as an upgrade",
        )
    }

    @Test
    fun `a build published the same instant is not an update either`() {
        val same = version("1.1", "2026-02-01", sha1 = "bbbb")
        assertNull(updateFrom(ref, "aaaa", "1.1", listOf(same), installedPublishedAt = "2026-02-01"))
    }

    @Test
    fun `a genuinely newer build still comes through`() {
        val newer = version("1.2", "2026-03-01", sha1 = "cccc")
        val update = updateFrom(ref, "aaaa", "1.1", listOf(newer), installedPublishedAt = "2026-02-01")!!
        assertEquals("1.2", update.versionNumber)
    }

    @Test
    fun `a file Modrinth cannot date falls back to the hash`() {
        val other = version("1.2", "2026-03-01", sha1 = "cccc")
        val update = updateFrom(ref, "aaaa", "1.1", listOf(other), installedPublishedAt = null)!!
        assertEquals("1.2", update.versionNumber, "no date to compare means the hash decides, as before")
    }

    // ── the channel follows what is installed ────────────────────────────────

    @Test
    fun `a beta on disk is asked about betas`() {
        assertEquals(ModUpdateChannel.Beta, effectiveChannel(ModUpdateChannel.Release, "beta"))
        assertEquals(ModUpdateChannel.Alpha, effectiveChannel(ModUpdateChannel.Release, "alpha"))
    }

    @Test
    fun `a release on disk keeps the instance preference`() {
        assertEquals(ModUpdateChannel.Release, effectiveChannel(ModUpdateChannel.Release, "release"))
        assertEquals(ModUpdateChannel.Release, effectiveChannel(ModUpdateChannel.Release, null))
        assertEquals(ModUpdateChannel.Beta, effectiveChannel(ModUpdateChannel.Beta, "release"))
    }

    @Test
    fun `the preference is a floor, never lowered by what is installed`() {
        assertEquals(ModUpdateChannel.Alpha, effectiveChannel(ModUpdateChannel.Alpha, "beta"))
        assertEquals(ModUpdateChannel.Alpha, effectiveChannel(ModUpdateChannel.Alpha, "release"))
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

    // ── what an install has to drag along ────────────────────────────────────

    private fun dep(project: String?, type: String = "required", versionId: String? = null) =
        ModrinthDependency(projectId = project, versionId = versionId, dependencyType = type)

    private fun withDeps(vararg deps: ModrinthDependency) =
        version("1.0", "2026-01-01", sha1 = "aaaa").copy(dependencies = deps.toList())

    @Test
    fun `required dependencies are what gets fetched`() {
        val v = withDeps(dep("curios"), dep("patchouli"))
        val needed = requiredDependencies(v, present = emptySet())
        assertEquals(listOf("curios", "patchouli"), needed.map { it.projectId })
    }

    @Test
    fun `optional, embedded and incompatible are left alone`() {
        val v = withDeps(
            dep("curios"),
            dep("jei", type = "optional"),
            dep("fabric_api", type = "embedded"),
            dep("embeddium", type = "incompatible"),
        )
        assertEquals(listOf("curios"), requiredDependencies(v, emptySet()).map { it.projectId },
            "a suggestion is not an instruction, and an embedded jar is already inside")
    }

    @Test
    fun `a dependency already installed is not fetched again`() {
        val v = withDeps(dep("curios"), dep("patchouli"))
        val needed = requiredDependencies(v, present = setOf("curios"))
        assertEquals(listOf("patchouli"), needed.map { it.projectId },
            "whatever version is already there stays: replacing it is how Sodium went out from under Iris")
    }

    @Test
    fun `a pinned dependency survives with its version id`() {
        val v = withDeps(dep("sodium", versionId = "Pb3OXVqC"))
        val needed = requiredDependencies(v, emptySet())
        assertEquals("Pb3OXVqC", needed.single().versionId, "the author pinned a build; the installer must honour it")
    }

    @Test
    fun `a dependency naming nothing at all is dropped`() {
        val v = withDeps(dep(null))
        assertTrue(requiredDependencies(v, emptySet()).isEmpty())
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
