package hivens.launcher

import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.data.InstanceRuntime
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PackInstallServiceTest {

    private lateinit var sandbox: Path

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        if (::sandbox.isInitialized) runCatching { sandbox.deleteRecursively() }
    }

    private val pack = CataloguePack(
        origin = PackOrigin.Mirror, id = "Industrial", title = "Industrial", tagline = "",
    )
    private val version = CataloguePackVersion(id = "2026.05.23", name = "May", versionNumber = "2026.05.23")

    private fun instance(id: String) = PackInstance(
        id = id,
        packRef = PackReference(PackOrigin.Mirror, "Industrial", "2026.05.23"),
        displayName = "Industrial",
        instanceDirName = "Industrial-$id",
        createdAtEpoch = 1_700_000_000L,
        runtime = InstanceRuntime(),
    )

    @Test
    fun `success path lands in Succeeded carrying the instance id`() = runTest {
        val service = PackInstallService(
            runInstall = { _, _, _, progress ->
                progress(1, 2, "mods/a.jar")
                instance("inst-1")
            },
            scope = this,
        )

        val key = service.start(pack, version)
        advanceUntilIdle()

        val phase = service.installs.value[key]?.phase
        assertIs<InstallPhase.Succeeded>(phase)
        assertEquals("inst-1", phase.instanceId)
    }

    @Test
    fun `failure path lands in Failed carrying the message`() = runTest {
        val service = PackInstallService(
            runInstall = { _, _, _, _ -> throw IOException("boom") },
            scope = this,
        )

        val key = service.start(pack, version)
        advanceUntilIdle()

        val phase = service.installs.value[key]?.phase
        assertIs<InstallPhase.Failed>(phase)
        assertEquals("boom", phase.message)
    }

    @Test
    fun `starting the same pack-version twice while running does not launch twice`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val launches = AtomicInteger(0)
        val service = PackInstallService(
            runInstall = { _, _, _, _ ->
                launches.incrementAndGet()
                gate.await()
                instance("inst-1")
            },
            scope = this,
        )

        val first = service.start(pack, version)
        val second = service.start(pack, version)
        advanceUntilIdle()

        assertEquals(first, second, "same (pack, version) returns the same key")
        assertEquals(1, launches.get(), "the running install is not started a second time")

        gate.complete(Unit)
        advanceUntilIdle()
        assertIs<InstallPhase.Succeeded>(service.installs.value[first]?.phase)
    }

    @Test
    fun `cancel removes the reserved partial dir and marks Cancelled`() = runTest {
        sandbox = Files.createTempDirectory("pack-install-test")
        val partial = sandbox.resolve("instances").resolve("Industrial-partial")
        val service = PackInstallService(
            runInstall = { _, _, onReserveDir, _ ->
                Files.createDirectories(partial)
                onReserveDir(partial)
                awaitCancellation()
            },
            scope = this,
        )

        val key = service.start(pack, version)
        advanceUntilIdle()
        assertTrue(Files.isDirectory(partial), "runner reserved and created the partial dir")

        service.cancel(key)
        advanceUntilIdle()

        assertFalse(Files.exists(partial), "cancel deletes the reserved partial dir")
        assertIs<InstallPhase.Cancelled>(service.installs.value[key]?.phase)
    }

    @Test
    fun `dismiss evicts a terminal snapshot`() = runTest {
        val service = PackInstallService(
            runInstall = { _, _, _, _ -> instance("inst-1") },
            scope = this,
        )

        val key = service.start(pack, version)
        advanceUntilIdle()
        assertIs<InstallPhase.Succeeded>(service.installs.value[key]?.phase)

        service.dismiss(key)
        assertNull(service.installs.value[key], "dismiss removes the snapshot")
    }
}
