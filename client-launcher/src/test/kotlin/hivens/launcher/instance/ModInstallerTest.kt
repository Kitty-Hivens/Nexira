package hivens.launcher.instance

import hivens.core.api.HttpClientProvider
import hivens.core.api.dto.modrinth.ModrinthDependency
import hivens.core.api.dto.modrinth.ModrinthFile
import hivens.core.api.dto.modrinth.ModrinthHashes
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.FileData
import hivens.core.data.fileManifestOf
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.launch.InstanceWork
import hivens.core.launch.InstanceWorkRegistry
import hivens.launcher.launch.RunningPackSource
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.update.PackFileRecord
import hivens.test.testTransferEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What installing a catalogue build does to a pack, through the installer the
 * project page and the pack's browser call.
 */
class ModInstallerTest {

    private val data: Path = Files.createTempDirectory("mod-install")

    @AfterTest
    fun cleanup() {
        data.toFile().deleteRecursively()
    }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    /** A real archive, so the scan reads it as content and the hash lookup asks about it. */
    private fun jar(marker: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("marker.txt"))
            z.write(marker.toByteArray())
            z.closeEntry()
        }
        return out.toByteArray()
    }

    private class FakeRepo : IPackRepository {
        val map = LinkedHashMap<String, PackInstance>()
        private val flow = MutableStateFlow<List<PackInstance>>(emptyList())
        override fun observe(): StateFlow<List<PackInstance>> = flow
        override suspend fun list(): List<PackInstance> = map.values.toList()
        override suspend fun get(id: String): PackInstance? = map[id]
        override suspend fun put(instance: PackInstance) {
            map[instance.id] = instance
            flow.value = map.values.toList()
        }
        override suspend fun delete(id: String) {
            map.remove(id)
            flow.value = map.values.toList()
        }
    }

    private val repo = FakeRepo()
    private val runningId = MutableStateFlow<String?>(null)
    private val running = object : RunningPackSource {
        override val runningPackInstanceId: StateFlow<String?> = runningId
    }
    private val work = InstanceWorkRegistry()

    /** A registered pack and its folder. */
    private class Pack(val id: String, val dir: Path)

    private suspend fun pack(origin: PackOrigin = PackOrigin.Local, loader: String = "neoforge", mc: String = "1.21.1"): Pack {
        val id = UUID.randomUUID().toString()
        val dir = Files.createDirectories(data.resolve("instances").resolve(id))
        repo.put(
            PackInstance(
                id = id,
                packRef = PackReference(origin, "p", "1"),
                displayName = "Pack",
                instanceDirName = id,
                createdAtEpoch = 0L,
                cachedManifest = CachedManifestSnapshot(mc, loader, "1", 21),
            ),
        )
        return Pack(id, dir)
    }

    /**
     * A catalogue that answers as told: each project's listing in the order it was
     * published here, every file by its own url, and the hash lookup over every
     * file it knows. Records which files were actually downloaded, and what the
     * pack was marked busy with while each one was.
     */
    private inner class Catalogue {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val listings = LinkedHashMap<String, MutableList<ModrinthVersion>>()
        private val bodies = HashMap<String, ByteArray>()
        private val byHash = LinkedHashMap<String, ModrinthVersion>()
        val fetched: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val busyDuringFetch: MutableList<InstanceWork?> = Collections.synchronizedList(mutableListOf())
        var watching: String? = null
        var hashLookupFails = false

        /** Every hash the catalogue was asked to identify, in the order asked. */
        val hashesAsked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val unservable = mutableSetOf<String>()

        /** File names the hash of which cannot be read, as a file held open elsewhere. */
        val unhashable = mutableSetOf<String>()

        fun publish(
            project: String,
            id: String,
            fileName: String = "$id.jar",
            loaders: List<String> = listOf("neoforge"),
            gameVersions: List<String> = listOf("1.21.1"),
            published: String = "2026-01-01T00:00:00Z",
            type: String = "release",
            deps: List<ModrinthDependency> = emptyList(),
            body: ByteArray = jar(id),
        ): ModrinthVersion {
            val url = "https://cdn.test/files/$id"
            val v = ModrinthVersion(
                id = id, projectId = project, name = id, versionNumber = id, versionType = type,
                gameVersions = gameVersions, loaders = loaders, datePublished = published,
                files = listOf(ModrinthFile(ModrinthHashes(sha1(body)), url, fileName, primary = true, size = body.size.toLong())),
                dependencies = deps,
            )
            listings.getOrPut(project) { mutableListOf() } += v
            bodies[url] = body
            byHash[sha1(body)] = v
            return v
        }

        fun body(v: ModrinthVersion): ByteArray = bodies.getValue(v.primaryFile().url)

        val client: ModrinthClient by lazy {
            val provider = HttpClientProvider {
                HttpClient(MockEngine { req ->
                    val path = req.url.encodedPath
                    if (req.url.host == "cdn.test") {
                        val url = req.url.toString()
                        val bytes = bodies[url]?.takeIf { url !in unservable }
                            ?: return@MockEngine respond(ByteReadChannel("no"), HttpStatusCode.NotFound)
                        fetched += url
                        watching?.let { busyDuringFetch += work.workOn(it) }
                        return@MockEngine respond(ByteReadChannel(bytes), HttpStatusCode.OK, headersOf("Content-Type", "application/java-archive"))
                    }
                    val listing = Regex("/v2/project/([^/]+)/version$").find(path)
                    val single = Regex("/v2/project/([^/]+)/version/([^/]+)$").find(path)
                    val text = when {
                        req.method == HttpMethod.Post && path.endsWith("/v2/version_files") -> {
                            hashesAsked += Regex("[0-9a-f]{40}").findAll(String(req.body.toByteArray())).map { it.value }
                            if (hashLookupFails) return@MockEngine respond(ByteReadChannel("down"), HttpStatusCode.ServiceUnavailable)
                            json.encodeToString(MapSerializer(String.serializer(), ModrinthVersion.serializer()), byHash)
                        }
                        single != null -> listings[single.groupValues[1]]?.firstOrNull { it.id == single.groupValues[2] }
                            ?.let { json.encodeToString(ModrinthVersion.serializer(), it) }
                        listing != null -> json.encodeToString(ListSerializer(ModrinthVersion.serializer()), listings[listing.groupValues[1]].orEmpty())
                        else -> null
                    } ?: return@MockEngine respond(ByteReadChannel("no"), HttpStatusCode.NotFound)
                    respond(ByteReadChannel(text.toByteArray()), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                })
            }
            ModrinthClient(provider, testTransferEngine(provider), json)
        }

        fun installer(): ModInstaller {
            val index = InstalledIndex(InstanceContentScanner(), client) {
                if (it.fileName.toString() in unhashable) null else runCatching { sha1(Files.readAllBytes(it)) }.getOrNull()
            }
            val core = ContentInstaller(client, index, InstanceContentManager(), work, running)
            return ModInstaller(core, index, repo, data)
        }

        suspend fun install(p: Pack, v: ModrinthVersion, loader: String = "neoforge", mc: String = "1.21.1", depth: Int = ContentInstaller.MAX_DEPTH) =
            installer().install(p.dir, v, mc, loader, depth)
    }

    private fun requires(project: String, versionId: String? = null, type: String = "required") =
        ModrinthDependency(projectId = project, versionId = versionId, dependencyType = type)

    private fun files(dir: Path, folder: String = "mods"): Set<String> =
        dir.resolve(folder).takeIf { Files.isDirectory(it) }
            ?.let { d -> Files.list(d).use { s -> s.map { it.fileName.toString() }.toList().toSet() } }
            .orEmpty()

    private fun put(p: Pack, folder: String, name: String, bytes: ByteArray) {
        Files.createDirectories(p.dir.resolve(folder))
        Files.write(p.dir.resolve(folder).resolve(name), bytes)
    }

    // ── what lands ──────────────────────────────────────────────────────────

    @Test
    fun `the clicked build lands with its required dependencies, a pin honoured over the newest`() = runTest {
        val c = Catalogue()
        c.publish("curios", "curios-2")
        c.publish("sodium", "sodium-2", published = "2026-03-01T00:00:00Z")
        c.publish("sodium", "sodium-1", published = "2026-01-01T00:00:00Z")
        c.publish("tips", "tips-1")
        val head = c.publish(
            "ars", "ars-1",
            deps = listOf(requires("curios"), requires("sodium", versionId = "sodium-1"), requires("tips", type = "optional")),
        )
        val p = pack()

        val outcome = c.install(p, head)

        assertEquals(listOf("ars-1.jar", "curios-2.jar", "sodium-1.jar"), outcome.installed, "head first, then its dependencies")
        assertEquals(setOf("ars", "curios", "sodium"), outcome.present)
        assertTrue(outcome.skipped.isEmpty())
        assertTrue(outcome.missing.isEmpty())
        assertTrue(outcome.ok)
        assertEquals(setOf("ars-1.jar", "curios-2.jar", "sodium-1.jar"), files(p.dir), "an optional dependency is not fetched")
    }

    @Test
    fun `an unpinned dependency is the newest release that runs here, not the first listed`() = runTest {
        val c = Catalogue()
        c.publish("curios", "curios-old", published = "2025-01-01T00:00:00Z")
        c.publish("curios", "curios-beta", published = "2026-06-01T00:00:00Z", type = "beta")
        c.publish("curios", "curios-new", published = "2026-03-01T00:00:00Z")
        c.publish("curios", "curios-fabric", published = "2026-07-01T00:00:00Z", loaders = listOf("fabric"))
        val head = c.publish("ars", "ars-1", deps = listOf(requires("curios")))
        val p = pack()

        c.install(p, head)

        assertEquals(setOf("ars-1.jar", "curios-new.jar"), files(p.dir))
    }

    @Test
    fun `a dependency already in the folder is kept as it is and named as skipped`() = runTest {
        val c = Catalogue()
        val old = c.publish("curios", "curios-1")
        c.publish("curios", "curios-2")
        val head = c.publish("ars", "ars-1", deps = listOf(requires("curios")))
        val p = pack()
        // Renamed by hand: the hash, not the name, is what says it is installed.
        put(p, "mods", "my-curios.jar", c.body(old))

        val outcome = c.install(p, head)

        assertEquals(listOf("ars-1.jar"), outcome.installed)
        assertEquals(listOf("curios"), outcome.skipped)
        assertEquals(setOf("ars-1.jar", "my-curios.jar"), files(p.dir))
        assertEquals(listOf(head.primaryFile().url), c.fetched.toList(), "nothing but the clicked build is downloaded")
    }

    @Test
    fun `a required dependency with no build for the pack is reported missing and the rest still lands`() = runTest {
        val c = Catalogue()
        c.publish("fabric-only", "fo-1", loaders = listOf("fabric"))
        val head = c.publish("ars", "ars-1", deps = listOf(requires("fabric-only")))
        val p = pack()

        val outcome = c.install(p, head)

        assertEquals(listOf("ars-1.jar"), outcome.installed)
        assertEquals(listOf("fabric-only"), outcome.missing)
        assertTrue(outcome.ok, "a missing dependency does not make the install itself fail")
    }

    @Test
    fun `a Quilt pack takes a Fabric dependency`() = runTest {
        val c = Catalogue()
        c.publish("fapi", "fapi-1", loaders = listOf("fabric"))
        val head = c.publish("mod", "mod-1", loaders = listOf("quilt"), deps = listOf(requires("fapi")))
        val p = pack(loader = "quilt")

        val outcome = c.install(p, head, loader = "quilt")

        assertEquals(listOf("mod-1.jar", "fapi-1.jar"), outcome.installed)
    }

    @Test
    fun `the walk fetches three levels of dependencies below the clicked build and names what lies further`() = runTest {
        val c = Catalogue()
        c.publish("p4", "p4-1")
        c.publish("p3", "p3-1", deps = listOf(requires("p4")))
        c.publish("p2", "p2-1", deps = listOf(requires("p3")))
        c.publish("p1", "p1-1", deps = listOf(requires("p2")))
        val head = c.publish("p0", "p0-1", deps = listOf(requires("p1")))
        val p = pack()

        val outcome = c.install(p, head)

        assertEquals(listOf("p0-1.jar", "p1-1.jar", "p2-1.jar", "p3-1.jar"), outcome.installed)
        assertEquals(listOf("p4"), outcome.missing, "a chain cut off reads as missing something, not as complete")
        assertTrue(ContentInstaller.Skip.TooDeep("p4") in outcome.skips)
        assertFalse("p4-1.jar" in files(p.dir))
        assertFalse(c.fetched.any { it.endsWith("p4-1") }, "nothing past the last level is fetched")
    }

    @Test
    fun `a dependency named from one branch and landed from another is not reported missing`() = runTest {
        // At the last level the walk names what it will not follow. A sibling queued
        // after the one asking is not planned yet when it is named, and then lands.
        val c = Catalogue()
        c.publish("b", "b-1")
        c.publish("a", "a-1", deps = listOf(requires("b")))
        val head = c.publish("h", "h-1", deps = listOf(requires("a"), requires("b")))
        val p = pack()

        val outcome = c.install(p, head, depth = 1)

        assertEquals(listOf("h-1.jar", "a-1.jar", "b-1.jar"), outcome.installed)
        assertTrue(outcome.missing.isEmpty(), "${outcome.missing}")
        assertTrue(outcome.skips.none { it.projectId == "b" }, "${outcome.skips}")
    }

    @Test
    fun `a folder that cannot take a download fails its own step and keeps what landed`() = runTest {
        val c = Catalogue()
        c.publish("bsl", "bsl-1", fileName = "bsl.zip", loaders = listOf("iris"))
        val head = c.publish("ars", "ars-1", deps = listOf(requires("bsl")))
        val p = pack()
        // A file where the shader folder should be: no scratch file can go in it.
        Files.writeString(p.dir.resolve("shaderpacks"), "not a folder")

        val outcome = c.install(p, head)

        assertTrue(outcome.ok, "the clicked build landed and the answer says so")
        assertEquals(listOf("ars-1.jar"), outcome.installed)
        assertEquals(listOf("bsl"), outcome.missing)
    }

    // ── a project the pack already has ──────────────────────────────────────

    @Test
    fun `another build of a present project replaces it instead of landing beside it`() = runTest {
        val c = Catalogue()
        val v1 = c.publish("sodium", "sodium-1")
        val v2 = c.publish("sodium", "sodium-2")
        val p = pack()
        put(p, "mods", "sodium-1.jar", c.body(v1))

        val outcome = c.install(p, v2)

        assertEquals(listOf("sodium-2.jar"), outcome.installed)
        assertEquals(setOf("sodium-2.jar"), files(p.dir))
    }

    @Test
    fun `a project turned off stays off when another build replaces it`() = runTest {
        val c = Catalogue()
        val v1 = c.publish("sodium", "sodium-1")
        val v2 = c.publish("sodium", "sodium-2")
        val p = pack()
        put(p, "mods", "sodium-1.jar.disabled", c.body(v1))

        c.install(p, v2)

        assertEquals(setOf("sodium-2.jar.disabled"), files(p.dir))
    }

    @Test
    fun `the very build already in the pack counts as installed and nothing is fetched`() = runTest {
        val c = Catalogue()
        val v = c.publish("sodium", "sodium-1")
        val p = pack()
        put(p, "mods", "renamed.jar", c.body(v))

        val outcome = c.install(p, v)

        assertTrue(outcome.ok)
        assertEquals(listOf("renamed.jar"), outcome.installed)
        assertTrue(c.fetched.isEmpty())
    }

    @Test
    fun `a build the pack itself placed is not replaced, and the reason is the outcome's`() = runTest {
        // An update of the pack would put its own file back beside the replacement.
        val c = Catalogue()
        val v1 = c.publish("sodium", "sodium-1")
        val v2 = c.publish("sodium", "sodium-2")
        val p = pack(origin = PackOrigin.Modrinth)
        put(p, "mods", "sodium-1.jar", c.body(v1))
        Files.writeString(p.dir.resolve(PackFileRecord.FILE_NAME), "${sha1(c.body(v1))} 0 0 - mods/sodium-1.jar\n")

        val outcome = c.install(p, v2)

        assertFalse(outcome.ok)
        assertEquals(ContentInstaller.Skip.PackOwned("sodium", ContentRef(ContentKind.Mod, "sodium-1.jar")), outcome.headLeftOut)
        assertEquals(setOf("sodium-1.jar"), files(p.dir))
        assertTrue(c.fetched.isEmpty())
    }

    @Test
    fun `a resource pack a mirror pack ships is not replaced, and one the player added is`() = runTest {
        // The mirror's sync and repair put the pack's file back by its path however
        // it was replaced, so the replacement would end up beside it.
        val c = Catalogue()
        val own1 = c.publish("faithful", "faithful-1", fileName = "faithful-1.zip", loaders = listOf("minecraft"))
        val own2 = c.publish("faithful", "faithful-2", fileName = "faithful-2.zip", loaders = listOf("minecraft"))
        val mine1 = c.publish("stay", "stay-1", fileName = "stay-1.zip", loaders = listOf("minecraft"))
        val mine2 = c.publish("stay", "stay-2", fileName = "stay-2.zip", loaders = listOf("minecraft"))
        val p = pack(origin = PackOrigin.Mirror)
        repo.put(
            repo.get(p.id)!!.copy(
                installedManifest = fileManifestOf(mapOf("resourcepacks/faithful-1.zip" to FileData(sha1 = sha1(c.body(own1)), size = 1))),
            ),
        )
        put(p, "resourcepacks", "faithful-1.zip", c.body(own1))
        put(p, "resourcepacks", "stay-1.zip", c.body(mine1))

        val refused = c.install(p, own2, loader = "", mc = "1.21.1")
        val replaced = c.install(p, mine2, loader = "", mc = "1.21.1")

        assertEquals(ContentInstaller.Skip.PackOwned("faithful", ContentRef(ContentKind.ResourcePack, "faithful-1.zip")), refused.headLeftOut)
        assertTrue(replaced.ok)
        assertEquals(setOf("faithful-1.zip", "stay-2.zip"), files(p.dir, "resourcepacks"))
    }

    @Test
    fun `a build the player put in a recorded pack is replaced as anywhere else`() = runTest {
        val c = Catalogue()
        val v1 = c.publish("sodium", "sodium-1")
        val v2 = c.publish("sodium", "sodium-2")
        val p = pack(origin = PackOrigin.Modrinth)
        put(p, "mods", "sodium-1.jar", c.body(v1))
        Files.writeString(p.dir.resolve(PackFileRecord.FILE_NAME), "abc 0 0 - mods/other.jar\n")

        val outcome = c.install(p, v2)

        assertTrue(outcome.ok)
        assertEquals(null, outcome.headLeftOut)
        assertEquals(setOf("sodium-2.jar"), files(p.dir))
    }

    @Test
    fun `a file whose bytes could not be read leaves the folder unknown and nothing is added`() = runTest {
        val c = Catalogue()
        val v1 = c.publish("sodium", "sodium-1")
        val v2 = c.publish("sodium", "sodium-2")
        val p = pack()
        put(p, "mods", "sodium-1.jar", c.body(v1))
        c.unhashable += "sodium-1.jar"

        val outcome = c.install(p, v2)

        assertFalse(outcome.ok, "unread, the old build would get a second one beside it")
        assertEquals(setOf("sodium-1.jar"), files(p.dir))
        assertTrue(c.fetched.isEmpty())
    }

    @Test
    fun `an archive whose metadata cannot be read leaves the folder unknown and nothing is added`() = runTest {
        // The scan skips it and the game loads it, so another build of the same mod
        // placed beside it would be a second copy the launcher never saw.
        val c = Catalogue()
        val v = c.publish("sodium", "sodium-2")
        val p = pack()
        val broken = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("fabric.mod.json"))
                z.write("{ not json".toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        put(p, "mods", "sodium-1.jar", broken)

        val outcome = c.install(p, v)

        assertFalse(outcome.ok)
        assertEquals(setOf("sodium-1.jar"), files(p.dir))
        assertTrue(c.fetched.isEmpty())
    }

    @Test
    fun `a name held by another file is left alone and the install does not land`() = runTest {
        val c = Catalogue()
        val head = c.publish("ars", "ars-1")
        val p = pack()
        val stranger = jar("not the published bytes")
        put(p, "mods", "ars-1.jar", stranger)

        val outcome = c.install(p, head)

        assertFalse(outcome.ok)
        assertTrue(c.fetched.isEmpty())
        assertContentEquals(stranger, Files.readAllBytes(p.dir.resolve("mods/ars-1.jar")))
    }

    @Test
    fun `a file name that leaves the folder is not placed`() = runTest {
        val c = Catalogue()
        val head = c.publish("evil", "evil-1", fileName = "../escaped.jar")
        val p = pack()

        val outcome = c.install(p, head)

        assertFalse(outcome.ok)
        assertFalse(Files.exists(p.dir.resolve("escaped.jar")))
        assertTrue(c.fetched.isEmpty())
    }

    // ── where it goes ───────────────────────────────────────────────────────

    @Test
    fun `a resource pack goes to resourcepacks and a shader to shaderpacks`() = runTest {
        val c = Catalogue()
        val rp = c.publish("faithful", "faithful-1", fileName = "faithful.zip", loaders = listOf("minecraft"))
        val shader = c.publish("bsl", "bsl-1", fileName = "bsl.zip", loaders = listOf("iris", "optifine"))
        val core = c.publish("vanilla-shader", "vs-1", fileName = "vs.zip", loaders = listOf("vanilla"))
        val p = pack()

        listOf(rp, shader, core).forEach { assertTrue(c.install(p, it).ok) }

        assertEquals(setOf("faithful.zip", "vs.zip"), files(p.dir, "resourcepacks"), "a vanilla shader is a resource pack")
        assertEquals(setOf("bsl.zip"), files(p.dir, "shaderpacks"))
        assertFalse(Files.exists(p.dir.resolve("mods")))
    }

    @Test
    fun `a mod that also ships as a data pack goes to mods in a pack that runs its loader`() = runTest {
        val c = Catalogue()
        val hybrid = c.publish("hybrid", "hybrid-1", loaders = listOf("neoforge", "datapack"))
        val p = pack()

        assertTrue(c.install(p, hybrid).ok)
        assertEquals(setOf("hybrid-1.jar"), files(p.dir))
    }

    @Test
    fun `a data pack alone, a modpack and a mod for another loader have no place`() = runTest {
        val c = Catalogue()
        val datapack = c.publish("dp", "dp-1", fileName = "dp.zip", loaders = listOf("datapack"))
        val modpack = c.publish("mp", "mp-1", fileName = "mp.mrpack", loaders = listOf("mrpack"))
        val fabric = c.publish("fm", "fm-1", loaders = listOf("fabric"))
        val p = pack()

        listOf(datapack, modpack, fabric).forEach { assertFalse(c.install(p, it).ok, it.id) }
        assertTrue(c.fetched.isEmpty())
    }

    @Test
    fun `a build the old installer put in mods goes where it belongs when it is installed again`() = runTest {
        val c = Catalogue()
        val v1 = c.publish("faithful", "faithful-1", fileName = "faithful-1.zip", loaders = listOf("minecraft"))
        val v2 = c.publish("faithful", "faithful-2", fileName = "faithful-2.zip", loaders = listOf("minecraft"))
        val p = pack()
        put(p, "mods", "faithful-1.zip", c.body(v1))

        assertTrue(c.install(p, v2).ok)

        assertEquals(setOf("faithful-2.zip"), files(p.dir, "resourcepacks"))
        assertTrue(files(p.dir).isEmpty())
    }

    @Test
    fun `what counts as present is read from every content folder`() = runTest {
        val c = Catalogue()
        val mod = c.publish("sodium", "sodium-1")
        val rp = c.publish("faithful", "faithful-1", fileName = "faithful.zip", loaders = listOf("minecraft"))
        val p = pack()
        put(p, "mods", "sodium-1.jar", c.body(mod))
        put(p, "resourcepacks", "faithful.zip", c.body(rp))

        assertEquals(setOf("sodium", "faithful"), c.installer().presentProjects(p.dir))
    }

    // ── which packs take what ───────────────────────────────────────────────

    @Test
    fun `a mirror pack takes a resource pack and refuses a mod`() = runTest {
        val c = Catalogue()
        val mod = c.publish("sodium", "sodium-1")
        val rp = c.publish("faithful", "faithful-1", fileName = "faithful.zip", loaders = listOf("minecraft"))
        val p = pack(origin = PackOrigin.Mirror)

        assertFalse(c.install(p, mod).ok, "the roster sweep would delete it before a bound launch")
        assertTrue(c.install(p, rp).ok)
        assertTrue(files(p.dir).isEmpty())
        assertEquals(setOf("faithful.zip"), files(p.dir, "resourcepacks"))
    }

    @Test
    fun `a Modrinth pack takes the player's files only while it keeps a record`() = runTest {
        val c = Catalogue()
        val mod = c.publish("sodium", "sodium-1")
        val bare = pack(origin = PackOrigin.Modrinth)
        val recorded = pack(origin = PackOrigin.Modrinth)
        Files.writeString(recorded.dir.resolve(PackFileRecord.FILE_NAME), "")

        assertFalse(c.install(bare, mod).ok)
        assertTrue(c.install(recorded, mod).ok)
        assertFalse(Files.readString(recorded.dir.resolve(PackFileRecord.FILE_NAME)).contains("sodium"), "the record stays the pack's")
    }

    @Test
    fun `a folder the installer does not know is refused`() = runTest {
        val c = Catalogue()
        val mod = c.publish("sodium", "sodium-1")
        val stray = Files.createDirectories(data.resolve("instances").resolve("unregistered"))

        assertFalse(c.installer().install(stray, mod, "1.21.1", "neoforge").ok)
        assertTrue(c.fetched.isEmpty())
    }

    // ── when it runs ────────────────────────────────────────────────────────

    @Test
    fun `nothing is installed into a pack whose game is running`() = runTest {
        val c = Catalogue()
        val mod = c.publish("sodium", "sodium-1")
        val p = pack()
        runningId.value = p.id

        assertFalse(c.install(p, mod).ok)
        assertTrue(c.fetched.isEmpty())
    }

    @Test
    fun `nothing is installed while other work rewrites the pack`() = runTest {
        val c = Catalogue()
        val mod = c.publish("sodium", "sodium-1")
        val p = pack()
        val release = CompletableDeferred<Unit>()
        val update = launch { work.during(p.id, InstanceWork.Update) { release.await() } }
        yield()

        assertFalse(c.install(p, mod).ok)
        assertTrue(c.fetched.isEmpty())
        assertEquals(InstanceWork.Update, work.workOn(p.id), "the refused install leaves no mark of its own")
        release.complete(Unit)
        update.join()
    }

    @Test
    fun `an install's mark refuses an update and, released, leaves the pack free for the next`() = runTest {
        val c = Catalogue()
        val first = c.publish("sodium", "sodium-1")
        val second = c.publish("iris", "iris-1")
        val p = pack()
        val claim = work.claim(p.id, InstanceWork.ContentInstall, alongside = setOf(InstanceWork.ContentInstall))
        val held = claim as InstanceWorkRegistry.Claim.Held

        assertEquals(InstanceWorkRegistry.Claim.Taken(InstanceWork.ContentInstall), work.claim(p.id, InstanceWork.Update))
        held.mark.release()
        assertTrue(c.install(p, first).ok)
        assertTrue(c.install(p, second).ok)
        assertEquals(null, work.workOn(p.id))
    }

    @Test
    fun `the pack is marked busy with the install while files are fetched, and free after`() = runTest {
        val c = Catalogue()
        val mod = c.publish("sodium", "sodium-1")
        val p = pack()
        c.watching = p.id

        assertTrue(c.install(p, mod).ok)

        assertEquals(listOf<InstanceWork?>(InstanceWork.ContentInstall), c.busyDuringFetch.toList())
        assertEquals(null, work.workOn(p.id))
    }

    @Test
    fun `when the clicked build does not download, its dependencies are not put in without it`() = runTest {
        val c = Catalogue()
        c.publish("curios", "curios-1")
        val head = c.publish("ars", "ars-1", deps = listOf(requires("curios")))
        c.unservable += head.primaryFile().url
        val p = pack()

        val outcome = c.install(p, head)

        assertFalse(outcome.ok)
        assertTrue(files(p.dir).none { it.endsWith(".jar") }, "${files(p.dir)}")
    }

    @Test
    fun `an install that did not run says why, and one that ran names what it left out`() = runTest {
        val c = Catalogue()
        c.publish("fabric-only", "fo-1", loaders = listOf("fabric"))
        val head = c.publish("ars", "ars-1", deps = listOf(requires("fabric-only")))
        val p = pack()

        runningId.value = p.id
        assertEquals(ContentInstaller.Refusal.GameRunning, c.install(p, head).refusal)
        runningId.value = null

        val outcome = c.install(p, head)
        assertEquals(null, outcome.refusal)
        assertEquals(listOf<ContentInstaller.Skip>(ContentInstaller.Skip.NoBuild("fabric-only")), outcome.skips)
    }

    // ── which packs a project can go into ───────────────────────────────────

    private fun Catalogue.targets() = InstallTargets(
        client,
        InstalledIndex(InstanceContentScanner(), client) {
            if (it.fileName.toString() in unhashable) null else runCatching { sha1(Files.readAllBytes(it)) }.getOrNull()
        },
        repo,
        data,
    )

    @Test
    fun `every pack gets an answer for a project, the ones it fits first`() = runTest {
        val c = Catalogue()
        val v1 = c.publish("sodium", "sodium-neo", loaders = listOf("neoforge"))
        c.publish("sodium", "sodium-fabric", loaders = listOf("fabric"), gameVersions = listOf("1.20.1"))
        val fits = pack()
        val has = pack()
        put(has, "mods", "sodium.jar", c.body(v1))
        val wrongLoader = pack(loader = "forge", mc = "1.12.2")
        val mirror = pack(origin = PackOrigin.Mirror)

        val answers = c.targets().forProject("sodium").associate { it.pack.id to it.verdict }

        assertEquals(InstallTargets.Verdict.Fits(v1), answers[fits.id])
        assertTrue(answers[has.id] is InstallTargets.Verdict.Present)
        assertEquals(InstallTargets.Verdict.NotFit(InstallTargets.Unfit.NoBuild), answers[wrongLoader.id])
        assertEquals(InstallTargets.Verdict.NotFit(InstallTargets.Unfit.NotTaken), answers[mirror.id])
        assertTrue(c.targets().forProject("sodium").first().verdict is InstallTargets.Verdict.Fits)
    }

    @Test
    fun `a resource pack fits a mirror pack and a pack with no loader`() = runTest {
        val c = Catalogue()
        val rp = c.publish("faithful", "faithful-1", fileName = "faithful.zip", loaders = listOf("minecraft"))
        val mirror = pack(origin = PackOrigin.Mirror)
        val vanilla = pack(loader = "vanilla")

        val answers = c.targets().forProject("faithful").associate { it.pack.id to it.verdict }

        assertEquals(InstallTargets.Verdict.Fits(rp), answers[mirror.id])
        assertEquals(InstallTargets.Verdict.Fits(rp), answers[vanilla.id])
    }

    @Test
    fun `a pack with a file that could not be hashed is unknown, not a fit`() = runTest {
        val c = Catalogue()
        c.publish("sodium", "sodium-1")
        val other = c.publish("other", "other-1")
        val p = pack()
        put(p, "mods", "other.jar", c.body(other))
        c.unhashable += "other.jar"

        assertEquals(InstallTargets.Verdict.Unknown, c.targets().forProject("sodium").single { it.pack.id == p.id }.verdict)
    }

    /**
     * The dialog opens from a project found anywhere, and every pack gets a row. Asked
     * of the catalogue, that was every file of every pack on every open, SmartyCraft
     * and mirror packs included, to answer a question about one project whose build
     * list was already in hand.
     */
    @Test
    fun `which packs a project could go into is answered without telling the catalogue what any pack holds`() = runTest {
        val c = Catalogue()
        val v1 = c.publish("sodium", "sodium-1")
        val v2 = c.publish("sodium", "sodium-2")
        val other = c.publish("other", "other-1")
        val has = pack()
        put(has, "mods", "renamed.jar", c.body(v1))
        put(has, "mods", "other.jar", c.body(other))
        val mirror = pack(origin = PackOrigin.Mirror)
        put(mirror, "resourcepacks", "faithful.zip", jar("the pack's own"))
        val smarty = pack(origin = PackOrigin.Smartycraft)
        put(smarty, "mods", "server-mod.jar", jar("the server's own"))

        val answers = c.targets().forProject("sodium").associate { it.pack.id to it.verdict }

        assertTrue(c.hashesAsked.isEmpty(), "asked about ${c.hashesAsked}")
        assertEquals(InstallTargets.Verdict.Present::class, answers.getValue(has.id)::class)
        assertEquals(v1, (answers.getValue(has.id) as InstallTargets.Verdict.Present).entry.version, "an older build, recognised by its bytes")
        assertTrue(v2.files.isNotEmpty())
    }

    @Test
    fun `a folder the catalogue could not identify gets nothing added`() = runTest {
        val c = Catalogue()
        val old = c.publish("curios", "curios-1")
        val head = c.publish("ars", "ars-1", deps = listOf(requires("curios")))
        val p = pack()
        put(p, "mods", "curios-1.jar", c.body(old))
        c.hashLookupFails = true

        val outcome = c.install(p, head)

        assertFalse(outcome.ok, "which file is which project is unknown, and guessing doubles a mod")
        assertEquals(setOf("curios-1.jar"), files(p.dir))
    }
}
