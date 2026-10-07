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
    fun `an entry from an older parser format reads as a miss`() {
        // A parser fix must refresh entries whose files never changed on disk;
        // pre-format entries default to v=1 and fail the format gate.
        val stale = """{"size":1,"mtime":2,"meta":{"name":"Truncated"}}"""
        env.executeInTransaction { txn ->
            env.openStore("content-scan", StoreConfig.WITHOUT_DUPLICATES, txn)
                .put(txn, StringBinding.stringToEntry("/i/mods/stale.jar"), ArrayByteIterable(stale.encodeToByteArray()))
        }
        assertNull(cache().lookup("/i/mods/stale.jar", 1L, 2L))
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

    /**
     * The second open of a pack reads the cache, so the cache has to carry
     * everything the parse found. The loader and the game version were added to
     * the parse and not to the stored record, and a jar therefore knew what it
     * ran on exactly once -- the first time its pack was opened.
     */
    @Test
    fun `a cached re-read keeps the loader and the game version`() = runTest {
        val instance = Files.createTempDirectory("inst")
        val mods = Files.createDirectories(instance.resolve("mods"))
        ZipOutputStream(Files.newOutputStream(mods.resolve("fab.jar"))).use { out ->
            out.putNextEntry(ZipEntry("fabric.mod.json"))
            out.write(
                """{"schemaVersion":1,"id":"fab","name":"Fab","version":"1.0","depends":{"minecraft":"1.21.1"}}"""
                    .toByteArray(),
            )
            out.closeEntry()
        }
        val c = cache()
        val jar = mods.resolve("fab.jar")
        val first = InstanceContentScanner(c).scan(instance).single()
        assertEquals(listOf("fabric"), first.loaders)
        assertEquals(listOf("1.21.1"), first.gameVersions)

        // Asked of the CACHE ENTRY, not of a second scan. A second scan re-parses
        // the jar whenever the entry is missing and answers correctly either way,
        // so it would have passed with the fields dropped on the way in.
        val stored = c.lookup(jar.normalize().toString(), Files.size(jar), Files.getLastModifiedTime(jar).toMillis())
        assertEquals(listOf("fabric"), stored?.meta?.loaders, "the entry itself carries the loaders")
        assertEquals(listOf("1.21.1"), stored?.meta?.gameVersions)

        val second = InstanceContentScanner(c).scan(instance).single()
        assertEquals(listOf("fabric"), second.loaders)
        assertEquals(listOf("1.21.1"), second.gameVersions)
        instance.toFile().deleteRecursively()
    }

    /**
     * A jar that declares BOTH manifests runs under both, and the scanner named
     * only whichever it happened to read first: a Fabric mod sitting in a Fabric
     * pack reported itself as NeoForge on its own page.
     */
    @Test
    fun `a multiloader jar names every loader it declares`() = runTest {
        val instance = Files.createTempDirectory("inst")
        val mods = Files.createDirectories(instance.resolve("mods"))
        ZipOutputStream(Files.newOutputStream(mods.resolve("both.jar"))).use { out ->
            out.putNextEntry(ZipEntry("fabric.mod.json"))
            out.write(FABRIC_TWO_VERSIONS.toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("META-INF/neoforge.mods.toml"))
            out.write(NEOFORGE_TOML.toByteArray())
            out.closeEntry()
        }
        val item = InstanceContentScanner(cache()).scan(instance).single()
        assertEquals(listOf("neoforge", "fabric"), item.loaders)
        instance.toFile().deleteRecursively()
    }

    /**
     * Fabric declares `depends.minecraft` as a set of alternatives. Taking the
     * first turned a mod built for the pack's own version into one built for the
     * version before it, on every mod whose author listed both.
     */
    @Test
    fun `a fabric jar keeps every game version it names`() = runTest {
        val instance = Files.createTempDirectory("inst")
        val mods = Files.createDirectories(instance.resolve("mods"))
        ZipOutputStream(Files.newOutputStream(mods.resolve("multi.jar"))).use { out ->
            out.putNextEntry(ZipEntry("fabric.mod.json"))
            out.write(FABRIC_TWO_VERSIONS.toByteArray())
            out.closeEntry()
        }
        val item = InstanceContentScanner(cache()).scan(instance).single()
        assertEquals(listOf("1.21", "1.21.1"), item.gameVersions)
        instance.toFile().deleteRecursively()
    }

    /**
     * A commented-out dependency block is a claim the manifest deliberately does
     * not make, and the flat line scan was reading it anyway.
     */
    @Test
    fun `a commented-out range is not a declared range`() = runTest {
        val instance = Files.createTempDirectory("inst")
        val mods = Files.createDirectories(instance.resolve("mods"))
        ZipOutputStream(Files.newOutputStream(mods.resolve("commented.jar"))).use { out ->
            out.putNextEntry(ZipEntry("META-INF/mods.toml"))
            out.write(COMMENTED_TOML.toByteArray())
            out.closeEntry()
        }
        val item = InstanceContentScanner(cache()).scan(instance).single()
        assertEquals(emptyList(), item.gameVersions)
        assertEquals(listOf("forge"), item.loaders)
        instance.toFile().deleteRecursively()
    }

    private companion object {
        val FABRIC_TWO_VERSIONS = """
            {"schemaVersion":1,"id":"m","name":"M","version":"1.0","depends":{"minecraft":["1.21","1.21.1"]}}
        """.trimIndent()

        val NEOFORGE_TOML = """
            modLoader="javafml"
            [[mods]]
            modId="m"
            displayName="M"
            [[dependencies.m]]
            modId="minecraft"
            versionRange="[1.21,)"
        """.trimIndent()

        val COMMENTED_TOML = """
            [[mods]]
            modId="c"
            displayName="C"
            #[[dependencies.c]]
            #modId="minecraft"
            #versionRange="[1.18,1.20.4]"
        """.trimIndent()
    }
}
