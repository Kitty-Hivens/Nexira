package hivens.launcher.instance

import jetbrains.exodus.ArrayByteIterable
import jetbrains.exodus.bindings.StringBinding
import jetbrains.exodus.env.Environment
import jetbrains.exodus.env.Environments
import jetbrains.exodus.env.StoreConfig
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

    private fun cache() = ContentScanCache(env, "content-scan", json)

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
    fun `retain drops deleted-file entries but keeps current and a sibling instance`() {
        val c = cache()
        c.put("/inst/A/mods/keep.jar", 1L, 1L, CachedMeta(name = "Keep"))
        c.put("/inst/A/mods/gone.jar", 1L, 1L, CachedMeta(name = "Gone"))
        c.put("/inst/A2/mods/other.jar", 1L, 1L, CachedMeta(name = "Other")) // shares the "A" name prefix

        c.retain("/inst/A/", setOf("/inst/A/mods/keep.jar"))

        assertEquals("Keep", c.lookup("/inst/A/mods/keep.jar", 1L, 1L)?.meta?.name)
        assertNull(c.lookup("/inst/A/mods/gone.jar", 1L, 1L))
        assertEquals("Other", c.lookup("/inst/A2/mods/other.jar", 1L, 1L)?.meta?.name) // sibling untouched
    }

    @Test
    fun `icon bytes round-trip through the Base64 field`() {
        val icon = ByteArray(1000) { (it * 31).toByte() }
        cache().put("/i/mods/icon.jar", 1L, 2L, CachedMeta(name = "Icon", icon = icon))
        val hit = cache().lookup("/i/mods/icon.jar", 1L, 2L)
        assertEquals(true, icon.contentEquals(hit?.meta?.icon))
    }

    @Test
    fun `an entry over the size floor caches without its icon instead of failing`() {
        // 2 MB icon -> ~2.7 MB as Base64 text, over the 1 MB entry floor. The
        // pre-guard behavior was a TooBigLoggableException from Xodus on every
        // scan; now the metadata must land minus the icon.
        val icon = ByteArray(2 * 1024 * 1024)
        cache().put("/i/mods/huge.jar", 1L, 2L, CachedMeta(name = "Huge", version = "3.0", icon = icon))
        val hit = cache().lookup("/i/mods/huge.jar", 1L, 2L)
        assertEquals("Huge", hit?.meta?.name)
        assertEquals("3.0", hit?.meta?.version)
        assertNull(hit?.meta?.icon)
    }

    @Test
    fun `an entry oversized even without an icon is skipped, not thrown`() {
        val monster = "x".repeat(2 * 1024 * 1024)
        cache().put("/i/mods/monster.jar", 1L, 2L, CachedMeta(name = "Monster", description = monster))
        assertNull(cache().lookup("/i/mods/monster.jar", 1L, 2L))
    }

    @Test
    fun `a legacy number-array icon entry reads as a miss`() {
        // Entries written before the Base64 icon field encode the icon as a JSON
        // number array. They must decode-fail into a plain miss (re-scan and
        // overwrite), never throw out of lookup.
        val legacy = """{"size":1,"mtime":2,"meta":{"name":"Old","icon":[1,2,3]}}"""
        env.executeInTransaction { txn ->
            env.openStore("content-scan", StoreConfig.WITHOUT_DUPLICATES, txn)
                .put(txn, StringBinding.stringToEntry("/i/mods/old.jar"), ArrayByteIterable(legacy.encodeToByteArray()))
        }
        assertNull(cache().lookup("/i/mods/old.jar", 1L, 2L))
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
        assertEquals("Cool Mod", c.lookup(jar.normalize().toString(), size, mtime)?.meta?.name)
        instance.toFile().deleteRecursively()
    }
}
