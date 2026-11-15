package hivens.catalogue

import hivens.catalogue.api.InstallOffer
import hivens.catalogue.api.ProjectPageView
import hivens.catalogue.api.ProjectTarget
import hivens.core.api.HttpClientProvider
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.net.TransferEngine
import hivens.launcher.instance.ModInstaller
import hivens.launcher.modrinth.ModrinthClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Path
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds

/** What the project page decides from what it has loaded. */
class ProjectPageModelTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("catalogue/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    private val project by lazy { json.decodeFromString<ModrinthProject>(resource("sodium.project.json")) }
    private val builds by lazy { json.decodeFromString<List<ModrinthVersion>>(resource("iris.versions.json")) }

    /** A client stood up and never reached: whatever a test asks of the network fails. */
    private fun offline(): ModrinthClient {
        val http = HttpClientProvider { error("no network in a page test") }
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

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = work.cancel()

    /** An installer that fails the first time it is asked and lands every time after. */
    private class FlakyInstall : PageInstall {
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun install(dir: Path, version: ModrinthVersion, mc: String, loader: String): ModInstaller.Outcome {
            asked += version.id
            if (asked.size == 1) throw IOException("the download broke")
            return ModInstaller.Outcome(installed = listOf("${version.id}.jar"))
        }

        override suspend fun presentProjects(dir: Path): Set<String> = emptySet()
    }

    /** An installer that lands once it is let go, and says when it was reached. */
    private class GatedInstall : PageInstall {
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        override suspend fun install(dir: Path, version: ModrinthVersion, mc: String, loader: String): ModInstaller.Outcome {
            reached.complete(Unit)
            gate.await()
            return ModInstaller.Outcome(installed = listOf("${version.id}.jar"), missing = listOf("sodium"))
        }

        override suspend fun presentProjects(dir: Path): Set<String> = emptySet()
    }

    /**
     * A page stood up as a load would leave it. Its own reads never run, the page's
     * scope being stopped, so nothing the offline client fails at writes over the seed.
     */
    private fun page(install: PageInstall): ProjectPageModel = ProjectPageModel(
        modrinth = offline(),
        packOf = { id -> pack.takeIf { it.id == id } },
        instancesDir = Path.of("/tmp/nexira-page-state/instances"),
        scanner = null,
        open = OpenProjects(),
        versionTags = GameVersionList { emptyList() },
        scope = CoroutineScope(Job().apply { cancel() }),
        work = work,
        pageInstall = install,
    ).also { model ->
        model.show(ProjectTarget.Catalogue(project.id, intoInstanceId = "inst"), "visit")
        model.seed(project, builds) { it.copy(install = InstallOffer.Install("Industrial")) }
    }

    private fun ProjectPageModel.await(what: String, until: (ProjectPageView) -> Boolean): ProjectPageView = runBlocking {
        try {
            withTimeout(5.seconds) { view.first { it != null && until(it) }!! }
        } catch (e: Exception) {
            throw AssertionError("$what never came: last was ${view.value}", e)
        }
    }

    /**
     * The header's Retry went through the newest build that fits, so a row's build
     * that failed was retried as a different build than the one the reader chose.
     */
    @Test
    fun `a retry repeats the build a row asked for`() {
        val install = FlakyInstall()
        val page = page(install)
        val chosen = builds.last()

        page.install(chosen.id)
        page.await("the failed install") { it.installFailed && !it.installing }
        page.retryInstall()
        page.await("the install that landed") { it.install is InstallOffer.Present }

        assertEquals(listOf(chosen.id, chosen.id), install.asked)
        assertEquals(InstallOffer.Present("Industrial"), page.view.value?.install)
    }

    /**
     * An install runs on the app's work, past the page it was clicked on. The page
     * shown by then is another visit, which asked for nothing, and the install's end
     * is not its news.
     */
    @Test
    fun `an install that ends after its page was left writes nothing over the next visit`() {
        val install = GatedInstall()
        val page = page(install)
        page.install(builds.last().id)
        runBlocking { withTimeout(5.seconds) { install.reached.await() } }

        page.show(ProjectTarget.Catalogue(project.id, intoInstanceId = "inst"), "later")
        val later = page.view.value
        install.gate.complete(Unit)
        runBlocking { withTimeout(5.seconds) { work.coroutineContext.job.children.toList().forEach { it.join() } } }

        val shown = checkNotNull(page.view.value)
        assertEquals("later", shown.visit)
        assertEquals(later, shown, "the visit that asked for nothing is as it was shown")
        assertNotEquals<InstallOffer>(InstallOffer.Present("Industrial"), shown.install)
        assertFalse(shown.installing)
        assertEquals(emptyList(), shown.installMissing)
    }
}
