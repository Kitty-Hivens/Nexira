package hivens.launcher.instance

import hivens.launcher.cache.XodusDiskStore
import jetbrains.exodus.env.Environment
import jetbrains.exodus.env.Environments
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContentScanCacheTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var dir: Path
    private lateinit var env: Environment

    @BeforeTest
    fun open() {
        dir = Files.createTempDirectory("scancache")
        env = Environments.newInstance(dir.toFile())
    }

    @AfterTest
    fun close() {
        env.close()
        dir.toFile().deleteRecursively()
    }

    private fun cache() = ContentScanCache(XodusDiskStore(env, "content-scan", CachedScan.serializer(), json))

    @Test
    fun `put then lookup hits on matching size and mtime`() {
        cache().put("/i/mods/a.jar", 100L, 200L, CachedMeta(name = "Cool Mod", version = "1.0"))
        val hit = cache().lookup("/i/mods/a.jar", 100L, 200L)
        assertEquals("Cool Mod", hit?.meta?.name)
        assertEquals("1.0", hit?.meta?.version)
    }

    @Test
    fun `lookup misses when size or mtime differs`() {
        cache().put("/i/mods/a.jar", 100L, 200L, CachedMeta(name = "X"))
        assertNull(cache().lookup("/i/mods/a.jar", 101L, 200L)) // size changed
        assertNull(cache().lookup("/i/mods/a.jar", 100L, 201L)) // mtime changed (a content update)
    }

    @Test
    fun `a null-meta entry (shader) round-trips as a hit, not a miss`() {
        cache().put("/i/shaderpacks/s.zip", 5L, 6L, null)
        val hit = cache().lookup("/i/shaderpacks/s.zip", 5L, 6L)
        assertEquals(5L, hit?.sizeBytes)
        assertEquals(null, hit?.meta)
    }

    @Test
    fun `an absent key misses`() {
        assertNull(cache().lookup("/i/mods/none.jar", 1L, 1L))
    }

    @Test
    fun `scanning a jar populates the cache with its parsed name`() = runTest {
        val instance = Files.createTempDirectory("inst")
        val mods = Files.createDirectories(instance.resolve("mods"))
        val jar = mods.resolve("cool.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(ZipEntry("fabric.mod.json"))
            out.write("""{"schemaVersion":1,"id":"cool","name":"Cool Mod","version":"1.0"}""".toByteArray())
            out.closeEntry()
        }
        val c = cache()
        val items = InstanceContentScanner(c).scan(instance)
        assertEquals("Cool Mod", items.single().displayName)

        val size = Files.size(jar)
        val mtime = Files.getLastModifiedTime(jar).toMillis()
        assertEquals("Cool Mod", c.lookup(jar.toString(), size, mtime)?.meta?.name)
        instance.toFile().deleteRecursively()
    }
}
