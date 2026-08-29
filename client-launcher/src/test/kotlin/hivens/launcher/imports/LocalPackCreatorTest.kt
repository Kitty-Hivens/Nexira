package hivens.launcher.imports

import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.launcher.runtime.RuntimeProvisioner
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class LocalPackCreatorTest {

    private val sandboxes = mutableListOf<Path>()

    @AfterTest
    fun tearDown() = sandboxes.forEach { runCatching { it.deleteRecursively() } }

    private fun tmp(name: String) = Files.createTempDirectory(name).also { sandboxes.add(it) }

    private class FakeRepo : IPackRepository {
        val stored = mutableListOf<PackInstance>()
        private val flow = MutableStateFlow<List<PackInstance>>(emptyList())
        override fun observe(): StateFlow<List<PackInstance>> = flow
        override suspend fun list() = stored.toList()
        override suspend fun get(id: String) = stored.firstOrNull { it.id == id }
        override suspend fun put(instance: PackInstance) { stored.add(instance); flow.value = stored.toList() }
        override suspend fun delete(id: String) { stored.removeAll { it.id == id }; flow.value = stored.toList() }
    }

    private fun creator(dataDir: Path, repo: IPackRepository, provisioner: RuntimeProvisioner = mockk(relaxed = true)): LocalPackCreator {
        val java = mockk<IJavaManager>()
        every { java.detectJavaVersion(any()) } returns 21
        return LocalPackCreator(provisioner, java, repo, dataDir)
    }

    @Test
    fun `creates an empty Fabric pack, seeds folders, provisions the runtime, registers it`() = runTest {
        val dataDir = tmp("data")
        val repo = FakeRepo()
        val provisioner = mockk<RuntimeProvisioner>(relaxed = true)

        val instance = creator(dataDir, repo, provisioner).create(
            name = "My Pack", mcVersion = "1.20.1", loader = "fabric", loaderVersion = "0.16.0",
        )

        val clientDir = dataDir.resolve("instances").resolve(instance.instanceDirName)
        assertTrue(Files.isDirectory(clientDir.resolve("mods")), "mods/ seeded for the browser to write into")
        assertTrue(Files.isDirectory(clientDir.resolve("config")))
        assertEquals(PackOrigin.Local, instance.packRef.origin)
        assertEquals("1.20.1", instance.cachedManifest?.minecraftVersion)
        assertEquals("fabric", instance.cachedManifest?.loaderName)
        assertEquals(1, repo.stored.size)
        coVerify(exactly = 1) { provisioner.ensureRuntime("1.20.1", "fabric", "0.16.0", any()) }
    }

    @Test
    fun `vanilla passes a null loader to the provisioner`() = runTest {
        val dataDir = tmp("data2")
        val provisioner = mockk<RuntimeProvisioner>(relaxed = true)
        creator(dataDir, FakeRepo(), provisioner).create(name = "Vanilla", mcVersion = "1.21.1", loader = "vanilla")
        coVerify(exactly = 1) { provisioner.ensureRuntime("1.21.1", null, "", any()) }
    }

    @Test
    fun `refuses a blank Minecraft version`() = runTest {
        val repo = FakeRepo()
        assertFailsWith<java.io.IOException> {
            creator(tmp("data3"), repo).create(name = "X", mcVersion = "  ", loader = null)
        }
        assertTrue(repo.stored.isEmpty())
    }
}
