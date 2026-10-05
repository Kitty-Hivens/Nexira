package hivens.launcher.instance

import hivens.core.api.HttpClientProvider
import hivens.core.launch.InstanceWorkRegistry
import hivens.launcher.modrinth.ModrinthClient
import hivens.test.testTransferEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.first
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
        val updater = InstanceContentUpdater(modrinth, InstanceContentManager(), backgroundScope, InstanceWorkRegistry())
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
}
