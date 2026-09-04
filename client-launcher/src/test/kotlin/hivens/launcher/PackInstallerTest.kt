package hivens.launcher

import hivens.core.api.HttpClientProvider
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.flatten
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.smrt.SmrtPackClient
import hivens.launcher.smrt.SmrtSyncService
import hivens.test.testTransferEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mirror install path, end to end over a mock mirror: which build's bytes land
 * on disk, and whether the record written beside them describes the same one.
 *
 * The pack here retains two builds. Everything the installer records came from the
 * manifest it was handed, while sync fetched the pack's current manifest itself, so
 * picking anything but the newest build produced an instance describing one build
 * over another build's files -- from the first second, on the ordinary path, since
 * the picker appears as soon as a pack has more than one retained build.
 */
class PackInstallerTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val temps = mutableListOf<Path>()

    @AfterTest
    fun cleanup() = temps.forEach { it.toFile().deleteRecursively() }

    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix).also { temps.add(it) }

    private fun sha1(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private val oldBytes = "MOD-AT-1.0.0".toByteArray()
    private val newBytes = "MOD-AT-2.0.0".toByteArray()

    // Two builds of one pack. They differ in the mod's bytes, in its filename and in
    // the Minecraft version, so a mismatch shows up in the files, in the roster and
    // in the recorded snapshot rather than in only one of the three.
    private fun manifestFor(version: String) = when (version) {
        OLD -> """
            {"schema_version":2,"pack_id":"test","pack_version":"$OLD","generated_at":"now",
             "minecraft":{"version":"1.20.1"},"loader":{"name":"fabric","version":"0.15.0"},"java":{"major":17},
             "mods":[{"filename":"pack-1.0.0.jar","sha1":"${sha1(oldBytes)}","size_bytes":${oldBytes.size},
                      "required":true,"source":{"type":"smrt_static","url":"$OLD_URL"}}],
             "assets":[]}
        """.trimIndent()
        else -> """
            {"schema_version":2,"pack_id":"test","pack_version":"$NEW","generated_at":"now",
             "minecraft":{"version":"1.21.1"},"loader":{"name":"fabric","version":"0.16.9"},"java":{"major":21},
             "mods":[{"filename":"pack-2.0.0.jar","sha1":"${sha1(newBytes)}","size_bytes":${newBytes.size},
                      "required":true,"source":{"type":"smrt_static","url":"$NEW_URL"}}],
             "assets":[]}
        """.trimIndent()
    }

    private val summaryBody = """
        {"pack_id":"test","display_name":"Test Pack","tagline":"t","minecraft_version":"1.21.1","latest_pack_version":"$NEW"}
    """.trimIndent()

    /** Serves the newest build at the unversioned manifest URL, as the mirror does. */
    private val engine = MockEngine { req ->
        when (val url = req.url.toString()) {
            SUMMARY_URL -> respond(summaryBody, HttpStatusCode.OK, jsonHeaders)
            LATEST_URL -> respond(manifestFor(NEW), HttpStatusCode.OK, jsonHeaders)
            "$LATEST_URL/$OLD" -> respond(manifestFor(OLD), HttpStatusCode.OK, jsonHeaders)
            "$LATEST_URL/$NEW" -> respond(manifestFor(NEW), HttpStatusCode.OK, jsonHeaders)
            OLD_URL -> respond(ByteReadChannel(oldBytes), HttpStatusCode.OK)
            NEW_URL -> respond(ByteReadChannel(newBytes), HttpStatusCode.OK)
            else -> respond("missing $url", HttpStatusCode.NotFound)
        }
    }

    private val provider = HttpClientProvider { HttpClient(engine) }
    private val client = SmrtPackClient(provider, MIRROR, json)

    private class FakeRepository : IPackRepository {
        private val state = MutableStateFlow<List<PackInstance>>(emptyList())
        override fun observe(): StateFlow<List<PackInstance>> = state
        override suspend fun list() = state.value
        override suspend fun get(id: String) = state.value.firstOrNull { it.id == id }
        override suspend fun put(instance: PackInstance) {
            state.value = state.value.filterNot { it.id == instance.id } + instance
        }
        override suspend fun delete(id: String) {
            state.value = state.value.filterNot { it.id == id }
        }
    }

    private fun installer(dataDir: Path, repository: IPackRepository): PackInstaller {
        val sync = SmrtSyncService(ModrinthClient(provider, testTransferEngine(provider), json), testTransferEngine(provider))
        // The runtime is Mojang's CDN and a shared directory, neither of which this
        // is about. Stubbed so the test stays about which build reached the instance.
        val provisioner = mockk<RuntimeProvisioner>()
        coEvery { provisioner.ensureRuntime(any(), any(), any(), any()) } returns mockk(relaxed = true)
        return PackInstaller(sync, provisioner, repository, dataDir)
    }

    private suspend fun install(dataDir: Path, repository: IPackRepository, version: String): PackInstance =
        installer(dataDir, repository).install(
            packId = "test",
            summary = json.decodeFromString(SmrtPackSummary.serializer(), summaryBody),
            manifest = json.decodeFromString(SmrtPackManifest.serializer(), manifestFor(version)),
        )

    @Test
    fun `installing a build that is not the newest puts that build on disk`() = runTest {
        val dataDir = tempDir("install-picked")
        val repository = FakeRepository()

        val instance = install(dataDir, repository, OLD)
        val clientDir = dataDir.resolve("instances").resolve(instance.instanceDirName)

        assertContentEquals(
            oldBytes,
            Files.readAllBytes(clientDir.resolve("mods/pack-1.0.0.jar")),
            "the picked build's mod is what should have been fetched",
        )
        assertFalse(
            Files.exists(clientDir.resolve("mods/pack-2.0.0.jar")),
            "the mirror's current build must not arrive instead of the one that was picked",
        )
    }

    @Test
    fun `the record beside the files describes the build the files are`() = runTest {
        val dataDir = tempDir("install-record")
        val repository = FakeRepository()

        val instance = install(dataDir, repository, OLD)
        val clientDir = dataDir.resolve("instances").resolve(instance.instanceDirName)

        // The pin, the cached snapshot and the baseline all come from the manifest
        // the installer was handed. They only mean anything if the bytes match them:
        // the launch reads the snapshot to pick a Java and a loader, and holds mods/
        // to the baseline's names.
        assertEquals(OLD, instance.pinnedPackVersion)
        assertEquals("1.20.1", instance.cachedManifest?.minecraftVersion)
        assertEquals(17, instance.cachedManifest?.javaMajor)
        assertEquals(
            setOf("mods/pack-1.0.0.jar"),
            instance.installedManifest?.flatten()?.keys,
            "the baseline names the files that were installed",
        )
        // Both variants of every mod: the roster is a set of names mods/ may hold,
        // and an optional the user turns off keeps the same bytes under the other one.
        assertEquals(
            setOf("pack-1.0.0.jar", "pack-1.0.0.jar.disabled"),
            Files.readAllLines(clientDir.resolve(".nexira-mods")).toSet(),
            "and so does the roster a launch is held to",
        )
    }

    @Test
    fun `installing the newest build still works`() = runTest {
        val dataDir = tempDir("install-newest")
        val repository = FakeRepository()

        val instance = install(dataDir, repository, NEW)
        val clientDir = dataDir.resolve("instances").resolve(instance.instanceDirName)

        assertContentEquals(newBytes, Files.readAllBytes(clientDir.resolve("mods/pack-2.0.0.jar")))
        assertEquals(NEW, instance.pinnedPackVersion)
        assertEquals("1.21.1", instance.cachedManifest?.minecraftVersion)
    }

    @Test
    fun `two installs of the same pack are independent instances`() = runTest {
        val dataDir = tempDir("install-twice")
        val repository = FakeRepository()

        val first = install(dataDir, repository, OLD)
        val second = install(dataDir, repository, NEW)

        assertTrue(first.id != second.id)
        assertTrue(first.instanceDirName != second.instanceDirName)
        assertEquals(2, repository.list().size, "both are registered")
        assertContentEquals(
            oldBytes,
            Files.readAllBytes(dataDir.resolve("instances").resolve(first.instanceDirName).resolve("mods/pack-1.0.0.jar")),
            "the second install must not reach into the first one's directory",
        )
    }

    private companion object {
        const val OLD = "1.0.0"
        const val NEW = "2.0.0"
        const val MIRROR = "https://mirror.test"
        const val SUMMARY_URL = "$MIRROR/v1/packs/test"
        const val LATEST_URL = "$MIRROR/v1/packs/test/manifest"
        const val OLD_URL = "$MIRROR/pack-1.0.0.jar"
        const val NEW_URL = "$MIRROR/pack-2.0.0.jar"
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
