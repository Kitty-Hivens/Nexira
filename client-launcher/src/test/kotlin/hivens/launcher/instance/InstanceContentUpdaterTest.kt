package hivens.launcher.instance

import hivens.core.api.HttpClientProvider
import hivens.core.api.dto.modrinth.ModrinthDependency
import hivens.core.api.dto.modrinth.ModrinthFile
import hivens.core.api.dto.modrinth.ModrinthHashes
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.launch.InstanceWorkRegistry
import hivens.launcher.modrinth.ModrinthClient
import hivens.test.testTransferEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstanceContentUpdaterTest {

    private val temps = mutableListOf<Path>()

    @AfterTest
    fun cleanup() = temps.forEach { it.toFile().deleteRecursively() }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    /**
     * The update knows the hash Modrinth published for the file. Handed to the
     * transfer, a body that arrives whole but wrong is fetched again. Checked only
     * after it, the first bad body failed the update.
     */
    @Test
    fun `an update whose first body arrives wrong is fetched again and lands`() = runTest {
        val dir = Files.createTempDirectory("content-update").also { temps.add(it) }
        Files.createDirectories(dir.resolve("mods"))
        Files.writeString(dir.resolve("mods/old.jar"), "OLD")
        val good = "NEW-BUILD".toByteArray()
        var served = 0
        val provider = HttpClientProvider {
            HttpClient(MockEngine { _ ->
                served++
                respond(ByteReadChannel(if (served == 1) "CORRUPTED".toByteArray() else good), HttpStatusCode.OK)
            })
        }
        val modrinth = ModrinthClient(provider, testTransferEngine(provider), Json { ignoreUnknownKeys = true })
        val updater = InstanceContentUpdater(modrinth, InstanceContentManager(), backgroundScope, InstanceWorkRegistry(), ModInstaller(modrinth, InstanceContentScanner()))
        val update = ModUpdate(
            ref = ContentRef(ContentKind.Mod, "old.jar"),
            installedVersion = "1.0",
            projectId = "p",
            versionId = "v",
            versionNumber = "1.1",
            versionType = "release",
            fileName = "new.jar",
            url = "https://cdn.test/new.jar",
            sha1 = sha1(good),
            sizeBytes = good.size.toLong(),
        )

        assertTrue(updater.start("instance", dir, "Pack", listOf(InstanceContentUpdater.Target(update, enabled = true))))
        val run = updater.runs.first { it[updater.keyOf(dir)]?.finished == true }.getValue(updater.keyOf(dir))

        assertEquals(emptyList(), run.failed)
        assertEquals(2, served)
        assertContentEquals(good, Files.readAllBytes(dir.resolve("mods/new.jar")))
        assertFalse(Files.exists(dir.resolve("mods/old.jar")))
    }

    /** A real archive, so the scan reads it as a mod. */
    private fun jar(marker: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("marker.txt"))
            z.write(marker.toByteArray())
            z.closeEntry()
        }
        return out.toByteArray()
    }

    private fun version(project: String, id: String, published: String, file: String, bytes: ByteArray, deps: List<ModrinthDependency> = emptyList()) =
        ModrinthVersion(
            id = id, projectId = project, name = id, versionNumber = id, versionType = "release",
            gameVersions = listOf("1.21.1"), loaders = listOf("neoforge"), datePublished = published,
            files = listOf(ModrinthFile(hashes = ModrinthHashes(sha1(bytes)), url = "https://cdn.test/$file", filename = file, primary = true, size = bytes.size.toLong())),
            dependencies = deps,
        )

    /**
     * An update to a build made against a newer library brings the library up to
     * the build it pinned, in the same batch. Left as it was, the game fails on
     * the first call into an API the old library does not have.
     */
    @Test
    fun `an update that pins a newer library brings the installed one up to it`() = runTest {
        val dir = Files.createTempDirectory("content-update").also { temps.add(it) }
        Files.createDirectories(dir.resolve("mods"))
        val oldMod = jar("mod 1"); val newMod = jar("mod 2"); val oldLib = jar("lib 1"); val newLib = jar("lib 2")
        Files.write(dir.resolve("mods/mod-1.jar"), oldMod)
        Files.write(dir.resolve("mods/lib-1.jar"), oldLib)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val modV1 = version("mod", "mod-1", "2026-01-01T00:00:00Z", "mod-1.jar", oldMod)
        val libV1 = version("lib", "lib-1", "2026-01-01T00:00:00Z", "lib-1.jar", oldLib)
        val libV2 = version("lib", "lib-2", "2026-03-01T00:00:00Z", "lib-2.jar", newLib)
        val hashes = json.encodeToString(MapSerializer(String.serializer(), ModrinthVersion.serializer()), mapOf(sha1(oldMod) to modV1, sha1(oldLib) to libV1))
        val provider = HttpClientProvider {
            HttpClient(MockEngine { req ->
                val path = req.url.encodedPath
                val (body, type) = when {
                    path.endsWith("/v2/version_files") -> hashes.toByteArray() to "application/json"
                    path.endsWith("/v2/project/lib/version/lib-2") -> json.encodeToString(ModrinthVersion.serializer(), libV2).toByteArray() to "application/json"
                    path.endsWith("/mod-2.jar") -> newMod to "application/java-archive"
                    path.endsWith("/lib-2.jar") -> newLib to "application/java-archive"
                    else -> return@MockEngine respond(ByteReadChannel("no"), HttpStatusCode.NotFound)
                }
                respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf("Content-Type", type))
            })
        }
        val modrinth = ModrinthClient(provider, testTransferEngine(provider), json)
        val updater = InstanceContentUpdater(modrinth, InstanceContentManager(), backgroundScope, InstanceWorkRegistry(), ModInstaller(modrinth, InstanceContentScanner()))
        val update = version("mod", "mod-2", "2026-03-02T00:00:00Z", "mod-2.jar", newMod, listOf(ModrinthDependency(projectId = "lib", versionId = "lib-2")))
            .swapFor(ContentRef(ContentKind.Mod, "mod-1.jar"), "mod-1")!!

        assertTrue(updater.start("instance", dir, "Pack", listOf(InstanceContentUpdater.Target(update, enabled = true))))
        val run = updater.runs.first { it[updater.keyOf(dir)]?.finished == true }.getValue(updater.keyOf(dir))

        assertEquals(emptyList(), run.failed)
        assertEquals(2, run.total, "the library joined the batch")
        assertContentEquals(newLib, Files.readAllBytes(dir.resolve("mods/lib-2.jar")))
        assertFalse(Files.exists(dir.resolve("mods/lib-1.jar")))
        assertContentEquals(newMod, Files.readAllBytes(dir.resolve("mods/mod-2.jar")))
    }
}
