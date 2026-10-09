package hivens.ui.feature.catalogue.project

import hivens.core.api.HttpClientProvider
import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.net.TransferEngine
import hivens.launcher.instance.InstanceContentScanner
import hivens.launcher.instance.ModInstaller
import hivens.launcher.modrinth.ModrinthClient
import hivens.ui.i18n.RussianStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the project page and a build's page decide from what they have loaded. */
class ProjectPageStateTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("catalogue/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    private val project by lazy { json.decodeFromString<ModrinthProject>(resource("sodium.project.json")) }
    private val builds by lazy { json.decodeFromString<List<ModrinthVersion>>(resource("iris.versions.json")) }

    /** A client stood up and never reached: whatever a test asks of the network fails. */
    private fun offline(): ModrinthClient {
        val http = HttpClientProvider { error("no network in a state test") }
        return ModrinthClient(http, TransferEngine(http))
    }

    private val pack = PackInstance(
        id = "inst",
        packRef = PackReference(PackOrigin.Local, "inst"),
        displayName = "Industrial",
        instanceDirName = "inst",
        createdAtEpoch = 0L,
        iconUrl = "",
        cachedManifest = CachedManifestSnapshot(minecraftVersion = "1.21.1", loaderName = "neoforge", loaderVersion = "21.1.200", javaMajor = 21),
    )

    private inner class Repo : IPackRepository {
        override fun observe(): StateFlow<List<PackInstance>> = MutableStateFlow(listOf(pack))
        override suspend fun list(): List<PackInstance> = listOf(pack)
        override suspend fun get(id: String): PackInstance? = pack.takeIf { it.id == id }
        override suspend fun put(instance: PackInstance) {}
        override suspend fun delete(id: String) {}
    }

    private fun state(install: ModDetailState.PageInstall? = null) = ModDetailState(
        target = ModTarget.Catalogue(project.id, intoInstanceId = "inst"),
        modrinth = offline(),
        repo = Repo(),
        dataDir = Path.of("/tmp/nexira-page-state"),
        scanner = InstanceContentScanner(),
        open = OpenProjectState(),
        strings = RussianStrings,
        installScope = CoroutineScope(Dispatchers.Unconfined),
        pageInstall = install,
    ).also {
        it.project = project
        it.loading = false
    }

    /** An installer that fails the first time it is asked and lands every time after. */
    private class FlakyInstall : ModDetailState.PageInstall {
        val asked = mutableListOf<String>()

        override suspend fun install(dir: Path, version: ModrinthVersion, mc: String, loader: String): ModInstaller.Outcome {
            asked += version.id
            if (asked.size == 1) throw IOException("the download broke")
            return ModInstaller.Outcome(installed = listOf("${version.id}.jar"))
        }

        override suspend fun presentProjects(dir: Path): Set<String> = emptySet()
    }

    /**
     * The header's Retry went through the newest build that fits, so a row's build
     * that failed was retried as a different build than the one the reader chose.
     */
    @Test
    fun `a retry repeats the build a row asked for`() = runBlocking {
        val install = FlakyInstall()
        val s = state(install)
        s.install = InstallAction.Install("Industrial")
        val chosen = builds.last()

        s.installVersion(chosen)
        assertTrue(s.installFailed)
        s.retryInstall()

        assertEquals(listOf(chosen.id, chosen.id), install.asked)
        assertEquals(InstallAction.Present("Industrial"), s.install)
    }

    /**
     * Without the catalogue's version list nothing can be folded, and the rail's
     * block came back empty, which read as a project that runs on nothing.
     */
    @Test
    fun `game versions read one chip each when the catalogue's list is not to hand`() {
        assertEquals(
            listOf(GameVersionGroup("1.21.1", listOf("1.21.1")), GameVersionGroup("1.20.1", listOf("1.20.1"))),
            gameVersionChips(listOf("1.21.1", "1.20.1"), emptyList()),
        )
        val tags = listOf("1.21.1", "1.21", "1.20.1").map { ModrinthGameVersion(it, versionType = ModrinthGameVersion.RELEASE) }
        assertEquals(listOf("1.21.x", "1.20.1"), gameVersionChips(listOf("1.21.1", "1.21", "1.20.1"), tags).map { it.label })
    }

    /** Before the page has loaded, every project has no shots: the tab restored on the way back survives that. */
    @Test
    fun `a Gallery tab waits for the page before it gives way`() {
        assertEquals(ModPageTab.Gallery, tabOnceLoaded(ModPageTab.Gallery, loading = true, noGallery = true))
        assertEquals(ModPageTab.Description, tabOnceLoaded(ModPageTab.Gallery, loading = false, noGallery = true))
        assertEquals(ModPageTab.Gallery, tabOnceLoaded(ModPageTab.Gallery, loading = false, noGallery = false))
        assertEquals(ModPageTab.Versions, tabOnceLoaded(ModPageTab.Versions, loading = false, noGallery = true))
    }

    /** A page whose project loaded without a catalogue entry has no build to wait for. */
    @Test
    fun `a build's page says there is nothing to ask about instead of waiting`() {
        val build = ModVersionState(offline())
        val noEntry = state().also { it.project = null }
        assertEquals(BuildPageView.NoEntry, buildPageView(noEntry, build))

        val loading = state().also { it.project = null; it.loading = true }
        assertEquals(BuildPageView.Loading, buildPageView(loading, build))

        val failed = state().also { it.failed = true }
        assertEquals(BuildPageView.Failed, buildPageView(failed, build))

        build.version = builds.first()
        assertEquals(BuildPageView.Ready, buildPageView(state(), build))
    }
}
