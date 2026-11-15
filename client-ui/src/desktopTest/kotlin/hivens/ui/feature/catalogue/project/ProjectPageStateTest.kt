package hivens.ui.feature.catalogue.project

import hivens.catalogue.api.BuildPageView
import hivens.catalogue.api.GameVersionGroup
import hivens.catalogue.api.GameVersionTag
import hivens.catalogue.api.ProjectPageView
import hivens.catalogue.api.ProjectTarget
import hivens.catalogue.api.gameVersionChips
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.ui.feature.catalogue.toBuild
import hivens.ui.feature.catalogue.toDetails
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the project page and a build's page decide from what they have loaded. */
class ProjectPageStateTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("catalogue/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    private val project by lazy { json.decodeFromString<ModrinthProject>(resource("sodium.project.json")) }
    private val builds by lazy { json.decodeFromString<List<ModrinthVersion>>(resource("iris.versions.json")) }

    /** The page as a load leaves it, the project read. */
    private fun state() = ProjectPageView(
        target = ProjectTarget.Catalogue(project.id, intoInstanceId = "inst"),
        visit = "test",
        loading = false,
        project = project.toDetails(),
    )

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
        val tags = listOf("1.21.1", "1.21", "1.20.1").map { GameVersionTag(it, versionType = GameVersionTag.RELEASE) }
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
        val build = BuildPageView(versionId = builds.first().id, visit = "test")
        val noEntry = state().copy(project = null)
        assertEquals(BuildPageState.NoEntry, buildPageView(noEntry, build))

        val loading = state().copy(project = null, loading = true)
        assertEquals(BuildPageState.Loading, buildPageView(loading, build))

        val failed = state().copy(failed = true)
        assertEquals(BuildPageState.Failed, buildPageView(failed, build))

        assertEquals(BuildPageState.Ready, buildPageView(state(), build.copy(build = builds.first().toBuild())))
    }
}
