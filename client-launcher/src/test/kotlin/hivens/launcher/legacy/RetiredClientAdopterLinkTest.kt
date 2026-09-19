package hivens.launcher.legacy

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the transfer does with a link, which a real client tree has in it.
 *
 * This matters more than the happy path because the caller deletes the source on
 * [RetiredClientAdopter.Adopted.complete]. Anything reported as complete and not
 * actually carried is content the player loses, and a link is exactly where that
 * goes wrong: the walk does not follow one, while every predicate that asks what
 * a path IS does follow it.
 */
class RetiredClientAdopterLinkTest {

    private lateinit var data: Path
    private lateinit var source: Path

    @BeforeTest
    fun setup() {
        data = Files.createTempDirectory("nexira-adopt-link-")
        source = (data / "clients" / "Industrial").also { it.createDirectories() }
        (source / "mods").createDirectories()
        (source / "mods" / "JEI.jar").writeText("jei")
    }

    @AfterTest
    fun teardown() {
        Files.walk(data).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun adopter() = RetiredClientAdopter(
        ensureRuntime = { _, _, _ -> },
        javaManager = NoJava,
        repository = NullRepo,
        dataDir = data,
        assetsDir = data / "assets",
    )

    private suspend fun adopt(): Pair<RetiredClientAdopter.Adopted, Path> {
        val adopted = adopter().adopt(
            RetiredClient("Industrial", source, 0L, "1.12.2", "forge", 0),
            "1.12.2",
            "forge",
        )
        return adopted to data / "instances" / adopted.instance.instanceDirName
    }

    /**
     * Worlds kept on another disk, which is why a player makes one of these.
     *
     * The bytes are not in the client tree, so the link is recreated pointing at
     * the same place and the source can still be let go. Removing the source then
     * removes a link and not a world.
     */
    @Test
    fun `a link out of the tree is carried as a link`() = runTest {
        val elsewhere = (data / "elsewhere" / "worlds").also { it.createDirectories() }
        (elsewhere / "level.dat").writeText("world")
        Files.createSymbolicLink(source / "saves", elsewhere)

        val (adopted, dir) = adopt()

        assertTrue(adopted.complete, "nothing was left behind, so the source carries nothing unique")
        assertTrue(Files.isSymbolicLink(dir / "saves"), "and it is still a link, not a copy")
        assertEquals("world", (dir / "saves" / "level.dat").readText())
    }

    /** The same for a single file, where a hardlink would have captured the link itself. */
    @Test
    fun `a linked file is carried as a link`() = runTest {
        val elsewhere = (data / "elsewhere").also { it.createDirectories() }
        (elsewhere / "Big.jar").writeText("payload")
        Files.createSymbolicLink(source / "mods" / "Big.jar", elsewhere / "Big.jar")

        val (adopted, dir) = adopt()

        assertTrue(adopted.complete)
        assertEquals("payload", (dir / "mods" / "Big.jar").readText())
    }

    /**
     * A link INTO the tree is refused, and refusing is what keeps the source.
     *
     * What it points at is what the sweep is about to remove, so recreating it
     * would hand the instance a path that stops existing minutes later. An
     * incomplete adoption keeps its folder, which is the only safe answer.
     */
    @Test
    fun `a link into the tree is not carried`() = runTest {
        (source / "worlds").createDirectories()
        (source / "worlds" / "level.dat").writeText("world")
        Files.createSymbolicLink(source / "saves", source / "worlds")

        val (adopted, _) = adopt()

        assertFalse(adopted.complete, "the target is about to be deleted, so this did not come across")
        assertTrue(adopted.failed > 0)
    }

    /** A link to nothing is a failure rather than something silently skipped. */
    @Test
    fun `a dangling link is not carried`() = runTest {
        Files.createSymbolicLink(source / "saves", data / "gone")

        val (adopted, _) = adopt()

        assertFalse(adopted.complete)
    }

    /**
     * The bytes that are now one inode shared with the source.
     *
     * The sweep subtracts these from what it reports as reclaimed, so the number
     * has to be the content that was hardlinked and not the whole folder.
     */
    @Test
    fun `hardlinked content is reported as shared`() = runTest {
        (source / "config").createDirectories()
        (source / "config" / "jei.cfg").writeText("0123456789")

        val (adopted, _) = adopt()

        assertTrue(adopted.complete)
        assertEquals(13L, adopted.sharedBytes, "three bytes of jei plus ten of config")
    }

    private object NoJava : hivens.core.api.interfaces.IJavaManager {
        override suspend fun getJavaPath(version: String): Path = Path.of("/usr/bin/java")
        override suspend fun getJavaPathForMajor(javaMajor: Int, onProgress: (String) -> Unit): Path =
            Path.of("/usr/bin/java")
    }

    private object NullRepo : hivens.core.api.interfaces.IPackRepository {
        private val flow = kotlinx.coroutines.flow.MutableStateFlow<List<hivens.core.data.PackInstance>>(emptyList())
        override fun observe() = flow
        override suspend fun list() = flow.value
        override suspend fun get(id: String) = null
        override suspend fun put(instance: hivens.core.data.PackInstance) {}
        override suspend fun delete(id: String) {}
    }
}
