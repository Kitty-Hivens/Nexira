package hivens.ui.screens.detail.versions

import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.update.PackBuild
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstalledBuildTest {

    private fun build(number: String, id: String? = null, mc: String? = null, loader: String? = null) =
        PackBuild(versionNumber = number, minecraftVersion = mc, loaderName = loader, id = id)

    private fun instance(
        version: String? = null,
        buildKey: String? = null,
        mc: String? = null,
        loader: String? = null,
    ) = PackInstance(
        id = "i",
        packRef = PackReference(PackOrigin.Modrinth, "MNW3LUwK", version),
        displayName = "Remarkably Optimized",
        instanceDirName = "remarkably",
        createdAtEpoch = 0L,
        pinnedPackVersion = version,
        installedBuildKey = buildKey,
        cachedManifest = mc?.let {
            CachedManifestSnapshot(
                minecraftVersion = it,
                loaderName = loader ?: "fabric",
                loaderVersion = "1",
                javaMajor = 21,
            )
        },
    )

    /** Three builds of one number, as Modrinth publishes them. */
    private val sharedNumber = listOf(
        build("1.15.56", id = "aaa", mc = "26.2", loader = "fabric"),
        build("1.15.56", id = "bbb", mc = "26.1.2", loader = "fabric"),
        build("1.15.56", id = "ccc", mc = "1.21.11", loader = "fabric"),
    )

    @Test fun `the recorded key names one build out of three sharing a number`() {
        val found = installedBuildOf(sharedNumber, instance(version = "1.15.56", buildKey = "bbb"))

        assertEquals("bbb", found?.key, "all three wear 1.15.56; only the key says which one is installed")
    }

    @Test fun `without a key the runtime tells them apart`() {
        val found = installedBuildOf(sharedNumber, instance(version = "1.15.56", mc = "1.21.11", loader = "fabric"))

        assertEquals("ccc", found?.key, "an instance from before the key was recorded still knows what it runs on")
    }

    @Test fun `a loader the source does not name does not rule a build out`() {
        val builds = listOf(
            build("2.0", id = "x", mc = "1.20.1"),
            build("2.0", id = "y", mc = "1.21.1"),
        )

        val found = installedBuildOf(builds, instance(version = "2.0", mc = "1.21.1", loader = "fabric"))

        assertEquals("y", found?.key)
    }

    @Test fun `nothing separates them and the newest is assumed`() {
        val found = installedBuildOf(sharedNumber, instance(version = "1.15.56"))

        assertEquals("aaa", found?.key, "the same guess as before, now the last resort rather than the only answer")
    }

    @Test fun `a unique label needs no key`() {
        val builds = listOf(build("5"), build("4"), build("3"))

        assertEquals("4", installedBuildOf(builds, instance(version = "4"))?.key)
    }

    @Test fun `a key that names no listed build falls back to the label`() {
        val found = installedBuildOf(sharedNumber, instance(version = "1.15.56", buildKey = "gone", mc = "26.2"))

        assertEquals("aaa", found?.key, "a build retired from the listing must not blank the marker")
    }

    @Test fun `an instance on no version matches nothing`() {
        assertNull(installedBuildOf(sharedNumber, instance()))
    }

    @Test fun `a label absent from the listing matches nothing`() {
        assertNull(installedBuildOf(sharedNumber, instance(version = "1.15.40")))
    }
}
