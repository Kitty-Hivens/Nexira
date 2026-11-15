package hivens.catalogue

import hivens.catalogue.api.BrowseView
import hivens.catalogue.api.FilterField
import hivens.catalogue.api.PackOrigins
import hivens.catalogue.api.ProjectKind
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.launcher.instance.ContentDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** What Browse knows about the pack installs go into, read again on every visit. */
class BrowseTargetReadTest {

    private val instancesDir = Path.of("/tmp/nexira-browse-target/instances")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun pack(mc: String, origin: PackOrigin = PackOrigin.Local) = PackInstance(
        id = "neo",
        packRef = PackReference(origin, "neo"),
        displayName = "NeoForge",
        instanceDirName = "neo",
        createdAtEpoch = 0L,
        iconUrl = "",
        cachedManifest = CachedManifestSnapshot(minecraftVersion = mc, loaderName = "neoforge", loaderVersion = "21.1.200", javaMajor = 21),
    )

    /** The pack the library answers with, swapped by a test between reads. */
    @Volatile
    private var answer: () -> PackInstance? = { pack("1.21.1") }

    private val browse = BrowseState(listOf(PackOrigins.MIRROR, PackOrigins.MODRINTH), { answer() }, instancesDir, scope)

    private fun await(what: String, until: (BrowseView) -> Boolean): BrowseView = runBlocking {
        try {
            withTimeout(5.seconds) { browse.view.first(until) }
        } catch (e: Exception) {
            throw AssertionError("$what never came: last was ${browse.view.value}", e)
        }
    }

    /**
     * Kept, the reading from before answered for a pack whose game version may have
     * moved since, and every install from the list asked for builds of the old one.
     */
    @Test
    fun `a pack that cannot be read drops the reading from before and keeps the choice`() {
        browse.setTarget("neo")
        await("the first reading") { it.target?.mcVersion == "1.21.1" }

        answer = { throw IOException("the database is locked") }
        browse.setTarget("neo")
        val after = await("the reading dropped") { it.target == null }

        assertNull(browse.target, "no reading answers for a pack that could not be read")
        assertEquals("neo", after.targetId, "unreadable is not gone")
    }

    @Test
    fun `a pack read again after an update reads as what it runs now`() {
        browse.setTarget("neo")
        await("the first reading") { it.target?.mcVersion == "1.21.1" }
        val before = assertNotNull(browse.target).destination

        answer = { pack("1.21.4") }
        browse.setTarget("neo")
        await("the second reading") { it.target?.mcVersion == "1.21.4" }

        // A search is told apart by this value, so it has to tell the two readings apart.
        assertNotEquals(before, assertNotNull(browse.target).destination)
    }

    @Test
    fun `a pack deleted while chosen is no longer the target`() {
        answer = { null }
        browse.setTarget("neo")
        await("the target dropped") { it.targetId == null }
    }

    @Test
    fun `aiming at a pack moves the search to a kind it takes, and only when asked`() {
        browse.setKind(null)
        browse.aimAt("neo")
        await("the kind moved") { it.target != null && it.kind == ProjectKind.Mod }

        // Picked since, it stays on a return that does not aim again.
        browse.setKind(ProjectKind.ShaderPack)
        browse.setTarget("neo")
        await("read again") { it.target != null }
        assertEquals(ProjectKind.ShaderPack, browse.view.value.kind)
    }

    @Test
    fun `a mirror pack takes no mods and a pack with no loader runs none`() {
        val mirror = ContentDestination.Pack.of(pack("1.21.1", PackOrigin.Mirror), instancesDir)
        assertEquals(listOf(ProjectKind.ResourcePack, ProjectKind.ShaderPack), BrowseState.kindsFor(mirror))
        val vanilla = ContentDestination.Pack.of(pack("1.21.1"), instancesDir).copy(loader = "")
        assertFalse(ProjectKind.Mod in BrowseState.kindsFor(vanilla))
    }

    @Test
    fun `the pack's locks narrow a search until a field is unlocked, and a new pack locks it again`() {
        browse.setKind(ProjectKind.Mod)
        browse.setTarget("neo")
        await("the locks") { it.locked.isNotEmpty() }
        assertEquals(setOf("1.21.1", "neoforge"), browse.view.value.locked.map { it.value }.toSet())

        browse.unlock(FilterField.GameVersion)
        assertEquals(listOf("neoforge"), browse.view.value.locked.map { it.value })

        browse.setTarget(null)
        assertEquals(emptySet(), browse.view.value.unlocked, "an unlocked field was a decision about one pack")
    }
}
