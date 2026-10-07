package hivens.ui.screens.detail.settings

import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import kotlin.test.Test
import kotlin.test.assertEquals

class PackLoaderSectionTest {

    private val pack = PackInstance(
        id = "1",
        packRef = PackReference(PackOrigin.Local, "mine", null),
        displayName = "Mine",
        instanceDirName = "mine",
        createdAtEpoch = 0L,
        notes = "kept",
        cachedManifest = CachedManifestSnapshot(
            minecraftVersion = "1.20.1",
            loaderName = "forge",
            loaderVersion = "47.2.0",
            javaMajor = 17,
        ),
    )

    @Test
    fun `a new version is written trimmed onto the record, and nothing else moves`() {
        val changed = withLoader(pack, "forge", " 47.3.0 ")
        assertEquals("47.3.0", changed.cachedManifest?.loaderVersion)
        assertEquals("forge", changed.cachedManifest?.loaderName)
        assertEquals("1.20.1", changed.cachedManifest?.minecraftVersion)
        assertEquals(pack.copy(cachedManifest = changed.cachedManifest), changed)
    }

    @Test
    fun `vanilla is stored by name and carries no version`() {
        val changed = withLoader(pack, null, "47.2.0")
        assertEquals("vanilla", changed.cachedManifest?.loaderName)
        assertEquals("", changed.cachedManifest?.loaderVersion)
    }

    @Test
    fun `a loader left on its latest stays blank for the next launch to pin`() {
        assertEquals("", withLoader(pack, "neoforge", "").cachedManifest?.loaderVersion)
    }
}
