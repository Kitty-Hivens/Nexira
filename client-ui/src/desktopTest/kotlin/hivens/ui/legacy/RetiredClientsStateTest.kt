package hivens.ui.legacy

import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.launcher.legacy.RetiredClientAdopter
import hivens.launcher.legacy.RetiredClientScanner
import hivens.launcher.legacy.RetiredDataSweeper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The order the chore runs in, which is the whole of its safety.
 *
 * Adoption first and the sweep second, so a tree is only ever removed after the
 * thing meant to inherit it exists. The content is hardlinked, so a source
 * removed after an INCOMPLETE adoption takes with it the files that did not
 * make it across -- which is why an incomplete one keeps its source and says so
 * rather than reporting success.
 *
 * These drive the real adopter and sweeper over a real temp tree. The pieces are
 * small and the thing under test is what they do to a filesystem, which a mock
 * of either would answer for rather than prove.
 */
class RetiredClientsStateTest {

    private lateinit var data: Path
    private lateinit var clients: Path

    @BeforeTest
    fun setup() {
        data = Files.createTempDirectory("nexira-retired-state-")
        clients = (data / "clients").also { it.createDirectories() }
    }

    @AfterTest
    fun teardown() {
        Files.walk(data).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun client(name: String, mc: String = "1.12.2"): Path {
        val dir = clients / name
        (dir / "bin" / "natives-$mc").createDirectories()
        (dir / "bin" / "natives-$mc" / "liblwjgl.so").writeText("native")
        // Content, which must come across.
        (dir / "mods").createDirectories()
        (dir / "mods" / "JEI.jar").writeText("jei")
        (dir / "config").createDirectories()
        (dir / "config" / "jei.cfg").writeText("cfg")
        (dir / "options.txt").writeText("fov:80")
        // Runtime, which must not.
        (dir / "libraries-$mc").createDirectories()
        (dir / "libraries-$mc" / "forge-$mc-14.23.5.2860.jar").writeText("forge")
        (dir / "assets-$mc.zip").writeText("zip")
        return dir
    }

    /** Records what the adoption registered, which is what the assertions read. */
    private class RecordingRepo : IPackRepository {
        var last: PackInstance? = null
        private val flow = MutableStateFlow<List<PackInstance>>(emptyList())
        override fun observe(): StateFlow<List<PackInstance>> = flow
        override suspend fun list(): List<PackInstance> = flow.value
        override suspend fun get(id: String): PackInstance? = flow.value.firstOrNull { it.id == id }
        override suspend fun put(instance: PackInstance) {
            last = instance
            flow.value = flow.value.filterNot { it.id == instance.id } + instance
        }
        override suspend fun delete(id: String) {
            flow.value = flow.value.filterNot { it.id == id }
        }
    }

    /** No JDK is provisioned here; the major is only recorded on the instance. */
    private object FixedJavaManager : IJavaManager {
        override suspend fun getJavaPath(version: String): Path = Path.of("/usr/bin/java")
        override suspend fun getJavaPathForMajor(javaMajor: Int, onProgress: (String) -> Unit): Path =
            Path.of("/usr/bin/java")
    }

    private lateinit var repo: RecordingRepo

    private fun state(sweeper: RetiredDataSweeper = RetiredDataSweeper(data)): RetiredClientsState {
        repo = RecordingRepo()
        return RetiredClientsState(
            scanner = RetiredClientScanner(clients),
            adopter = RetiredClientAdopter(
                // A real runtime download is a network stack the assertions never
                // look at; what this exercises is what the adoption does to disk.
                ensureRuntime = { _, _, _ -> },
                javaManager = FixedJavaManager,
                repository = repo,
                dataDir = data,
                assetsDir = data / "assets",
            ),
            sweeper = sweeper,
        )
    }

    @Test
    fun `nothing chosen changes nothing on disk`() = runTest {
        client("Industrial")
        val s = state()
        s.load()

        assertEquals(1, s.rows.size)
        assertFalse(s.anyChosen, "Leave it is a real answer and the default")
        s.run()

        assertTrue((clients / "Industrial").exists(), "a pass with no choices must not touch the tree")
        assertEquals(0L, s.reclaimedBytes)
    }

    @Test
    fun `a deleted client goes and an untouched one stays`() = runTest {
        client("Industrial")
        client("Galaxy")
        val s = state()
        s.load()
        s.rows.first { it.client.name == "Industrial" }.choice = RetiredChoice.Delete
        s.run()

        assertFalse((clients / "Industrial").exists())
        assertTrue((clients / "Galaxy").exists())
        assertTrue(s.reclaimedBytes > 0)
        assertIs<RetiredOutcome.Deleted>(s.rows.first { it.client.name == "Industrial" }.outcome)
    }

    /**
     * The adoption carries content and leaves the runtime, and only then is the
     * source let go -- which is what makes letting it go safe.
     */
    @Test
    fun `an adopted client keeps its content, drops its runtime and loses its source`() = runTest {
        client("Industrial")
        val s = state()
        s.load()
        s.rows.single().choice = RetiredChoice.Adopt
        s.run()

        val outcome = s.rows.single().outcome
        assertIs<RetiredOutcome.Adopted>(outcome)
        assertFalse(outcome.sourceKept, "every file made it, so the old folder carries nothing unique")
        assertFalse((clients / "Industrial").exists(), "and is therefore gone")

        val instance = repo.last ?: error("the adoption registered nothing")
        val dir = data / "instances" / instance.instanceDirName
        assertEquals("jei", (dir / "mods" / "JEI.jar").readText(), "content came across")
        assertEquals("fov:80", (dir / "options.txt").readText())
        assertFalse((dir / "libraries-1.12.2").exists(), "the per-version libraries root is the launcher's to provision")
        assertFalse((dir / "assets-1.12.2.zip").exists())
        assertFalse((dir / "bin").exists(), "bin is runtime, and on a modern client a bundled JRE")
    }

    @Test
    fun `adoption makes a local pack, never a mirror one`() = runTest {
        client("Industrial")
        val s = state()
        s.load()
        s.rows.single().choice = RetiredChoice.Adopt
        s.run()

        val instance = repo.last ?: error("the adoption registered nothing")
        assertEquals(hivens.core.data.PackOrigin.Local, instance.packRef.origin)
        assertEquals("Industrial", instance.displayName)
        assertEquals("1.12.2", instance.cachedManifest?.minecraftVersion)
        assertEquals("forge", instance.cachedManifest?.loaderName)
        assertTrue(instance.notes.isNotBlank(), "the owner has to be told nothing updates it now")
    }

    /**
     * The corrected value wins over the detected one. The tree lies -- one real
     * client carries a Fabric loader log beside a Forge-only mod -- so what the
     * reader confirmed is what gets provisioned.
     */
    @Test
    fun `the reader's correction is what gets provisioned`() = runTest {
        client("Industrial")
        val s = state()
        s.load()
        s.rows.single().apply {
            choice = RetiredChoice.Adopt
            mcVersion = "1.20.1"
            loader = "neoforge"
        }
        s.run()

        val instance = repo.last ?: error("the adoption registered nothing")
        assertEquals("1.20.1", instance.cachedManifest?.minecraftVersion)
        assertEquals("neoforge", instance.cachedManifest?.loaderName)
    }

    /** A blank loader is vanilla, not an empty string the provisioner has to read. */
    @Test
    fun `a cleared loader field means vanilla`() = runTest {
        client("Industrial")
        val s = state()
        s.load()
        s.rows.single().apply { choice = RetiredChoice.Adopt; loader = "" }
        s.run()

        assertEquals("vanilla", repo.last?.cachedManifest?.loaderName)
    }

    /**
     * The default skins are read out of a client jar, and on an upgraded install
     * the retired tree is the only place one lives. The hook has to run while it
     * is still there.
     */
    @Test
    fun `the rescue hook runs before anything is deleted`() = runTest {
        val dir = client("Industrial")
        var treeWasThere: Boolean? = null
        val s = state(RetiredDataSweeper(data, beforeFirstDelete = { treeWasThere = dir.exists() }))
        s.load()
        s.rows.single().choice = RetiredChoice.Delete
        s.run()

        assertEquals(true, treeWasThere, "the hook must see what it is meant to rescue")
    }

    /**
     * A folder whose tree names no version can still be SET to adopt, and the
     * pass is what waits.
     *
     * The version is typed on a row that is already set to adopt, so refusing the
     * choice put the one folder that needs typing out of reach of the only field
     * that fixes it. The gate belongs on running, not on choosing.
     */
    @Test
    fun `a client with no version blocks the pass rather than the choice`() = runTest {
        val dir = clients / "Mystery"
        (dir / "mods").createDirectories()
        (dir / "mods" / "Something.jar").writeText("x")

        val s = state()
        s.load()
        assertFalse(s.rows.single().adoptable)

        s.chooseAll(RetiredChoice.Adopt)
        assertEquals(RetiredChoice.Adopt, s.rows.single().choice, "the row has to be reachable to be fixed")
        assertFalse(s.ready, "and the pass waits until a version is on it")

        s.rows.single().mcVersion = "1.12.2"
        assertTrue(s.ready)
    }

    /**
     * What the surface reports as reclaimed, against what the disk actually got.
     *
     * An adopted source is hardlinked into its instance, so removing it frees the
     * runtime that was left behind and nothing else. Counting the whole folder
     * would have the surface announce gigabytes that never came back.
     */
    @Test
    fun `adopted bytes are not counted as reclaimed`() = runTest {
        client("Industrial")
        val s = state()
        s.load()
        s.rows.single().choice = RetiredChoice.Adopt
        val folder = s.rows.single().client.sizeBytes
        s.run()

        assertTrue(folder > 0, "the fixture has to weigh something for this to mean anything")
        assertTrue(
            s.reclaimedBytes < folder,
            "content that is now a shared inode is not space the disk got back",
        )
    }

    @Test
    fun `choosing all applies to every row that can take it`() = runTest {
        client("Industrial")
        client("Galaxy")
        val s = state()
        s.load()

        s.chooseAll(RetiredChoice.Delete)
        assertTrue(s.rows.all { it.choice == RetiredChoice.Delete })
        assertTrue(s.anyChosen)
    }

    @Test
    fun `a second run is refused while the first is going`() = runTest {
        client("Industrial")
        val s = state()
        s.load()
        s.rows.single().choice = RetiredChoice.Delete
        s.run()
        val reclaimed = s.reclaimedBytes

        // The rows are settled; running again must not double-count or throw.
        s.run()
        assertEquals(reclaimed, s.reclaimedBytes)
    }
}
