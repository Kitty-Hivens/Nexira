package hivens.launcher.instance

import jetbrains.exodus.env.Environments
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InstanceContentScannerTest {

    @TempDir lateinit var dir: Path

    private fun zip(target: Path, entries: Map<String, ByteArray>) {
        Files.createDirectories(target.parent)
        ZipOutputStream(Files.newOutputStream(target)).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    @Test
    fun `parses fabric mod name, version and icon and detects disabled`() = runBlocking {
        val mods = dir.resolve("mods")
        zip(mods.resolve("sodium-fabric-0.6.0-mc1.21.jar"), mapOf(
            "fabric.mod.json" to """{"id":"sodium","name":"Sodium","version":"0.6.0","description":"Fast","icon":"icon.png"}""".toByteArray(),
            "icon.png" to byteArrayOf(1, 2, 3, 4),
        ))
        zip(mods.resolve("iris-1.8.0.jar.disabled"), mapOf(
            "fabric.mod.json" to """{"id":"iris","name":"Iris Shaders","version":"1.8.0"}""".toByteArray(),
        ))

        val items = InstanceContentScanner().scan(dir)

        val sodium = items.first { it.fileName == "sodium-fabric-0.6.0-mc1.21.jar" }
        assertEquals("Sodium", sodium.displayName)
        assertEquals("0.6.0", sodium.version)
        assertTrue(sodium.enabled)
        assertNotNull(sodium.iconBytes)
        assertEquals(4, sodium.iconBytes!!.size)

        val iris = items.first { it.fileName == "iris-1.8.0.jar" }
        assertEquals("Iris Shaders", iris.displayName)
        assertFalse(iris.enabled, "a .disabled jar reads as disabled")
        assertEquals(ContentKind.Mod, iris.kind)
    }

    @Test
    fun `falls back to a cleaned filename when a jar carries no metadata`() = runBlocking {
        zip(dir.resolve("mods").resolve("SomeMod-1.2.3.jar"), mapOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray()))

        val item = InstanceContentScanner().scan(dir).single()
        assertEquals("SomeMod", item.displayName)
        assertNull(item.version)
    }

    @Test
    fun `reads resource pack description and classifies by folder`() = runBlocking {
        zip(dir.resolve("resourcepacks").resolve("Faithful.zip"), mapOf(
            "pack.mcmeta" to """{"pack":{"pack_format":15,"description":"Faithful 32x"}}""".toByteArray(),
            "pack.png" to byteArrayOf(9, 9),
        ))

        val item = InstanceContentScanner().scan(dir).single()
        assertEquals(ContentKind.ResourcePack, item.kind)
        assertEquals("Faithful 32x", item.description)
        assertNotNull(item.iconBytes)
    }

    @Test
    fun `bound icon processor rewrites both the returned item and the cached entry`() = runBlocking {
        val mods = dir.resolve("mods")
        val jar = mods.resolve("big-icon.jar")
        zip(jar, mapOf(
            "fabric.mod.json" to """{"id":"big","name":"Big","version":"1.0","icon":"icon.png"}""".toByteArray(),
            "icon.png" to ByteArray(512) { it.toByte() },
        ))
        val marker = byteArrayOf(7, 7, 7)
        val env = Environments.newInstance(Files.createTempDirectory("scanenv").toFile())
        try {
            val cache = ContentScanCache(env, "content-scan", Json { ignoreUnknownKeys = true })
            val items = InstanceContentScanner(cache, icons = { marker }).scan(dir)
            assertTrue(marker.contentEquals(items.single().iconBytes), "returned item carries the processed icon")

            val size = Files.size(jar)
            val mtime = Files.getLastModifiedTime(jar).toMillis()
            val hit = cache.lookup(jar.normalize().toString(), size, mtime)
            assertTrue(marker.contentEquals(hit?.meta?.icon), "cache stores the processed icon, not the original")
        } finally {
            env.close()
        }
    }

    @Test
    fun `processor dropping the icon leaves the rest of the metadata intact`() = runBlocking {
        zip(dir.resolve("mods").resolve("dropped.jar"), mapOf(
            "fabric.mod.json" to """{"id":"d","name":"Dropped","version":"2.0","icon":"icon.png"}""".toByteArray(),
            "icon.png" to ByteArray(64),
        ))

        val item = InstanceContentScanner(icons = { null }).scan(dir).single()
        assertEquals("Dropped", item.displayName)
        assertEquals("2.0", item.version)
        assertNull(item.iconBytes)
    }
}
