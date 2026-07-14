package hivens.launcher.imports

import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.launcher.runtime.RuntimeProvisioner
import io.mockk.every
import io.mockk.mockk
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class ForeignInstanceImporterTest {

    private val sandboxes = mutableListOf<Path>()

    @AfterTest
    fun tearDown() = sandboxes.forEach { runCatching { it.deleteRecursively() } }

    private fun tmp(name: String): Path = Files.createTempDirectory(name).also { sandboxes.add(it) }

    private class FakeRepo : IPackRepository {
        val stored = mutableListOf<PackInstance>()
        private val flow = MutableStateFlow<List<PackInstance>>(emptyList())
        override fun observe(): Flow<List<PackInstance>> = flow
        override suspend fun list() = stored.toList()
        override suspend fun get(id: String) = stored.firstOrNull { it.id == id }
        override suspend fun put(instance: PackInstance) { stored.add(instance); flow.value = stored.toList() }
        override suspend fun delete(id: String) { stored.removeAll { it.id == id }; flow.value = stored.toList() }
    }

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    /** A foreign game dir with instance content, a vanilla-layout runtime, and noise to skip. */
    private fun buildSource(): Path {
        val src = tmp("foreign-src")
        write(src.resolve("mods/Example.jar"), "MOD")
        write(src.resolve("config/example.cfg"), "CFG")
        write(src.resolve("saves/World/level.dat"), "SAVE")
        write(src.resolve("options.txt"), "OPT")
        // Vanilla-layout runtime -> should dedupe into the shared roots, NOT the instance.
        write(src.resolve("assets/objects/ab/abcdef123"), "ASSET")
        write(src.resolve("assets/indexes/17.json"), "INDEX")
        write(src.resolve("libraries/net/example/lib/1.0/lib-1.0.jar"), "LIB")
        write(src.resolve("versions/1.21.1/1.21.1.jar"), "CLIENT")
        // Noise -> skipped.
        write(src.resolve("logs/latest.log"), "LOG")
        write(src.resolve("launcher_accounts.json"), "SECRET")
        return src
    }

    private fun importer(dataDir: Path, libs: Path, assets: Path, repo: IPackRepository): ForeignInstanceImporter {
        val provisioner = mockk<RuntimeProvisioner>(relaxed = true)
        val java = mockk<IJavaManager>()
        every { java.detectJavaVersion(any()) } returns 21
        return ForeignInstanceImporter(provisioner, java, repo, dataDir, libs, assets)
    }

    @Test
    fun `imports content, dedups the vanilla runtime, registers a Local instance`() = runTest {
        val src = buildSource()
        val dataDir = tmp("nexira-data")
        val libs = dataDir.resolve("libraries")
        val assets = dataDir.resolve("assets")
        val repo = FakeRepo()

        val instance = importer(dataDir, libs, assets, repo).import(
            DiscoveredInstance(
                launcher = ForeignLauncher.Ftb, id = "sb4", displayName = "StoneBlock 4",
                gameDir = src, mcVersion = "1.21.1", loader = "neoforge", loaderVersion = "21.1.1",
            ),
        )

        val clientDir = dataDir.resolve("instances").resolve(instance.instanceDirName)
        // Content copied.
        assertEquals("MOD", Files.readString(clientDir.resolve("mods/Example.jar")))
        assertEquals("CFG", Files.readString(clientDir.resolve("config/example.cfg")))
        assertEquals("SAVE", Files.readString(clientDir.resolve("saves/World/level.dat")))
        assertEquals("OPT", Files.readString(clientDir.resolve("options.txt")))
        // Runtime + noise NOT copied into the instance.
        assertFalse(Files.exists(clientDir.resolve("assets")), "assets belong in the shared roots, not the instance")
        assertFalse(Files.exists(clientDir.resolve("libraries")))
        assertFalse(Files.exists(clientDir.resolve("versions")))
        assertFalse(Files.exists(clientDir.resolve("logs")))
        assertFalse(Files.exists(clientDir.resolve("launcher_accounts.json")), "launcher secrets are not carried")
        // Runtime deduped into the shared roots (+ client-jar remap to the maven coord).
        assertEquals("ASSET", Files.readString(assets.resolve("objects/ab/abcdef123")))
        assertEquals("INDEX", Files.readString(assets.resolve("indexes/17.json")))
        assertEquals("LIB", Files.readString(libs.resolve("net/example/lib/1.0/lib-1.0.jar")))
        assertEquals("CLIENT", Files.readString(libs.resolve("net/minecraft/minecraft/1.21.1/minecraft-1.21.1.jar")))
        // Registered.
        assertEquals(1, repo.stored.size)
        assertEquals(PackOrigin.Local, instance.packRef.origin)
        assertEquals("1.21.1", instance.cachedManifest?.minecraftVersion)
        assertEquals("neoforge", instance.cachedManifest?.loaderName)
    }

    @Test
    fun `refuses an instance with no resolvable Minecraft version`() = runTest {
        val dataDir = tmp("nexira-data2")
        val repo = FakeRepo()
        assertFailsWith<java.io.IOException> {
            importer(dataDir, dataDir.resolve("libraries"), dataDir.resolve("assets"), repo).import(
                DiscoveredInstance(
                    launcher = ForeignLauncher.Vanilla, id = "root", displayName = ".minecraft",
                    gameDir = tmp("bare"), mcVersion = null,
                ),
            )
        }
        assertTrue(repo.stored.isEmpty(), "a refused import registers nothing")
    }

    @Test
    fun `an instance without a vanilla-layout runtime still imports (no seeding)`() = runTest {
        val src = tmp("modrinth-like")
        write(src.resolve("mods/A.jar"), "A")
        write(src.resolve("options.txt"), "O")
        val dataDir = tmp("nexira-data3")
        val libs = dataDir.resolve("libraries")
        val assets = dataDir.resolve("assets")
        val repo = FakeRepo()

        val instance = importer(dataDir, libs, assets, repo).import(
            DiscoveredInstance(
                launcher = ForeignLauncher.Prism, id = "p", displayName = "Prism Pack",
                gameDir = src, mcVersion = "1.20.1", loader = "fabric", loaderVersion = "0.16.0",
            ),
        )
        val clientDir = dataDir.resolve("instances").resolve(instance.instanceDirName)
        assertEquals("A", Files.readString(clientDir.resolve("mods/A.jar")))
        assertFalse(Files.exists(assets), "no vanilla-layout source -> shared roots left untouched")
        assertEquals(1, repo.stored.size)
    }
}
