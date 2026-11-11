package hivens.ui.feature.catalogue.browse

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** What Browse knows about the pack installs go into, read again on every visit. */
class BrowseTargetReadTest {

    private val dataDir = Path.of("/tmp/nexira-browse-target")

    private fun pack(mc: String) = PackInstance(
        id = "neo",
        packRef = PackReference(PackOrigin.Local, "neo"),
        displayName = "NeoForge",
        instanceDirName = "neo",
        createdAtEpoch = 0L,
        iconUrl = "",
        cachedManifest = CachedManifestSnapshot(minecraftVersion = mc, loaderName = "neoforge", loaderVersion = "21.1.200", javaMajor = 21),
    )

    private class Repo(var answer: () -> PackInstance?) : IPackRepository {
        override fun observe(): StateFlow<List<PackInstance>> = MutableStateFlow(emptyList())
        override suspend fun list(): List<PackInstance> = listOfNotNull(answer())
        override suspend fun get(id: String): PackInstance? = answer()
        override suspend fun put(instance: PackInstance) {}
        override suspend fun delete(id: String) {}
    }

    /**
     * Kept, the reading from before answered for a pack whose game version may have
     * moved since, and every install from the list asked for builds of the old one.
     */
    @Test
    fun `a pack that cannot be read drops the reading from before and keeps the choice`() = runBlocking {
        val c = BrowseController()
        c.targetId = "neo"
        val repo = Repo { pack("1.21.1") }
        readTarget(c, "neo", repo, dataDir)
        assertEquals("1.21.1", c.target?.destination?.target?.mcVersion)

        repo.answer = { throw IOException("the database is locked") }
        readTarget(c, "neo", repo, dataDir)

        assertNull(c.resolved, "no reading answers for a pack that could not be read")
        assertEquals("neo", c.targetId, "unreadable is not gone")
    }

    @Test
    fun `a pack read again after an update reads as what it runs now`() = runBlocking {
        val c = BrowseController()
        c.targetId = "neo"
        val repo = Repo { pack("1.21.1") }
        readTarget(c, "neo", repo, dataDir)
        val before = c.target?.destination?.target

        repo.answer = { pack("1.21.4") }
        readTarget(c, "neo", repo, dataDir)

        assertEquals("1.21.4", c.target?.destination?.target?.mcVersion)
        // The list's state is remembered against this value, so it has to tell the two apart.
        assertNotEquals(before, c.target?.destination?.target)
    }

    @Test
    fun `a pack deleted while chosen is no longer the target`() = runBlocking {
        val c = BrowseController()
        c.targetId = "neo"
        readTarget(c, "neo", Repo { null }, dataDir)
        assertNull(c.targetId)
    }
}
