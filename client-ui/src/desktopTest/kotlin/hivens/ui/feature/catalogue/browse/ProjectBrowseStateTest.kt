package hivens.ui.feature.catalogue.browse

import hivens.core.api.dto.modrinth.ModrinthSearchHit
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.launch.InstanceWork
import hivens.launcher.instance.ContentInstaller
import hivens.launcher.instance.ContentKind
import hivens.launcher.instance.ModInstaller
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Installing a mod used to be a `runCatching` in a click lambda with the
 * success line outside it, so a download that threw still marked the row
 * Installed -- the user read "done" and the jar was not there. The state holder
 * makes the outcome a value the row can render, and now says why a row failed.
 */
class ProjectBrowseStateTest {

    private fun hit(id: String) = ModrinthSearchHit(projectId = id, slug = id, title = id)

    /** An install that landed, reporting what the instance holds afterwards. */
    private fun landed(vararg present: String) =
        ModInstaller.Outcome(installed = listOf("mod.jar"), present = present.toSet())

    private val nothing = ModInstaller.Outcome()

    private val destination = browseDestination(
        PackInstance(id = "p", packRef = PackReference(PackOrigin.Local, "p"), displayName = "Probe", instanceDirName = "probe", createdAtEpoch = 0L),
        Path.of("/tmp/nexira-test-data"),
    )

    private fun state(
        search: suspend (String, Int, Int, Collection<String>) -> List<ModrinthSearchHit> = { _, _, _, _ -> emptyList() },
        install: suspend (ModrinthSearchHit) -> ModInstaller.Outcome? = { landed(it.projectId) },
        present: suspend () -> Set<String> = { emptySet() },
        hideInstalled: Boolean = false,
    ) = ProjectBrowseState(ContentKind.Mod, destination, search, install, present, hideInstalled)

    @Test
    fun `a successful install marks the project installed`() = runTest {
        val state = state()

        state.install(hit("jei"))

        assertEquals(setOf("jei"), state.present)
        assertTrue(state.problems.isEmpty())
        assertTrue(state.working.isEmpty(), "the row must not stay spinning after the work ends")
    }

    @Test
    fun `a download that throws does not report success`() = runTest {
        val state = state(install = { throw IOException("connection reset") })

        state.install(hit("jei"))

        assertFalse("jei" in state.present, "reporting an install that did not happen is the bug this replaced")
        assertEquals(mapOf("jei" to InstallProblem.NotLanded), state.problems)
        assertTrue(state.working.isEmpty())
    }

    @Test
    fun `a project with no build for this pack says so`() = runTest {
        val state = state(install = { null })

        state.install(hit("jei"))

        assertFalse("jei" in state.present)
        assertEquals(mapOf("jei" to InstallProblem.NoBuild), state.problems)
    }

    @Test
    fun `an install the pack refused says why`() = runTest {
        val state = state(install = { ModInstaller.Outcome(refusal = ContentInstaller.Refusal.Busy(InstanceWork.Update)) })

        state.install(hit("jei"))

        assertEquals(mapOf("jei" to InstallProblem.Refused(ContentInstaller.Refusal.Busy(InstanceWork.Update))), state.problems)
    }

    @Test
    fun `retrying clears the previous failure`() = runTest {
        var succeed = false
        val state = state(install = { if (succeed) landed(it.projectId) else nothing })

        state.install(hit("jei"))
        assertEquals(mapOf("jei" to InstallProblem.NotLanded), state.problems)

        succeed = true
        state.install(hit("jei"))

        assertEquals(setOf("jei"), state.present)
        assertTrue(state.problems.isEmpty(), "a row that succeeded on retry must stop reading as failed")
    }

    @Test
    fun `a failed search shows an empty result rather than a spinner forever`() = runTest {
        val state = state(search = { _, _, _, _ -> throw IOException("offline") })

        state.search("jei")

        assertEquals(emptyList(), state.results, "null is the in-flight state; a dead search must leave it")
        assertTrue(state.searchFailed)
    }

    @Test
    fun `the folder decides what reads as installed, not this session`() = runTest {
        val state = state(present = { setOf("sodium", "iris", "jei") })

        state.loadPresent()

        assertEquals(setOf("sodium", "iris", "jei"), state.present,
            "a pack of ninety mods must not be offered its own contents to install")
    }

    @Test
    fun `a dependency pulled in behind an install stops offering itself`() = runTest {
        // Installing Iris drags Sodium along, and Sodium has its own row in the
        // same result list.
        val state = state(install = { landed("iris", "sodium") })

        state.install(hit("iris"))

        assertTrue("sodium" in state.present, "the dependency landed, so its row is not an install")
        assertTrue("iris" in state.present)
    }

    @Test
    fun `a folder that cannot be read leaves the browser usable`() = runTest {
        val state = state(present = { throw IOException("offline") })

        state.loadPresent()

        assertTrue(state.present.isEmpty(), "unknown is not installed")
    }

    @Test
    fun `search results reach the browser`() = runTest {
        val state = state(search = { _, _, _, _ -> listOf(hit("jei"), hit("journeymap")) })

        state.search("j")

        assertEquals(listOf("jei", "journeymap"), state.results?.map { it.projectId })
    }

    @Test
    fun `a search hears what the pack holds before its first page`() = runTest {
        // Hiding what is installed is asked of the catalogue by id, so the folder
        // has to be read before the question goes out, not beside it.
        var heard: Collection<String>? = null
        val state = state(
            present = { setOf("sodium") },
            search = { _, _, _, excluded -> heard = excluded; emptyList() },
            hideInstalled = true,
        )

        state.search("")

        assertEquals(setOf("sodium"), heard?.toSet())
    }

    @Test
    fun `a search that does not hide asks the catalogue to leave nothing out`() = runTest {
        var heard: Collection<String>? = null
        val state = state(present = { setOf("sodium") }, search = { _, _, _, excluded -> heard = excluded; emptyList() })

        state.search("")

        assertEquals(emptySet(), heard?.toSet())
    }

    @Test
    fun `an install from the list does not move the pages after it`() = runTest {
        // The next page is asked with the exclusion the first one had. One more
        // excluded id moves every later result up a place, and the page read next
        // would begin a result late, so one project would never be shown.
        val asked = mutableListOf<Pair<Int, Set<String>>>()
        val state = state(
            present = { setOf("sodium") },
            search = { _, offset, _, excluded ->
                asked += offset to excluded.toSet()
                if (offset == 0) (1..30).map { hit("p$it") } else listOf(hit("p31"))
            },
            install = { landed("sodium", it.projectId) },
            hideInstalled = true,
        )

        state.search("")
        state.install(hit("p3"))
        state.more()

        assertEquals(listOf(0 to setOf("sodium"), 30 to setOf("sodium")), asked)
        assertTrue("p3" in state.present)
        assertTrue(state.results.orEmpty().any { it.projectId == "p3" }, "the row installed from stays, reading installed")
    }

    @Test
    fun `a pack too big to name in the query is hidden here instead`() = runTest {
        val held = (1..250).map { "m$it" }.toSet()
        var heard = 0
        val state = state(
            present = { held },
            search = { _, _, _, excluded -> heard = excluded.size; listOf(hit("m240"), hit("fresh")) },
            hideInstalled = true,
        )

        state.search("")

        assertEquals(200, heard, "every excluded id is a clause in the query")
        assertEquals(listOf("fresh"), state.results?.map { it.projectId })
    }

    @Test
    fun `a page the pack's own projects filled is read past`() = runTest {
        val held = (1..230).map { "m$it" }.toSet()
        val state = state(
            present = { held },
            search = { _, offset, _, _ ->
                if (offset == 0) (201..230).map { hit("m$it") } else listOf(hit("fresh"))
            },
            hideInstalled = true,
        )

        state.search("")

        assertEquals(listOf("fresh"), state.results?.map { it.projectId }, "an emptied page is not the end of the list")
    }

    @Test
    fun `the next page adds what is new and stops when nothing is`() = runTest {
        val first = (1..30).map { hit("p$it") }
        val pages = mapOf(0 to first, 30 to listOf(hit("p30"), hit("p31")), 32 to listOf(hit("p31")))
        val state = state(search = { _, offset, _, _ -> pages[offset].orEmpty() })

        state.search("")
        state.more()
        state.more()

        assertEquals((1..31).map { "p$it" }, state.results?.map { it.projectId }, "a repeat is not a second row")
    }

    // -- a search kept for the session ------------------------------------------

    private class Clock(var now: Long = 0L)

    private fun keyed(
        session: ProjectBrowseSession,
        calls: MutableList<Pair<String, Int>>,
        present: suspend () -> Set<String> = { emptySet() },
    ) = ProjectBrowseState(
        ContentKind.Mod,
        destination,
        search = { q, offset, _, _ -> calls += q to offset; if (offset == 0) (1..30).map { hit("$q$it") } else listOf(hit("${q}x")) },
        installInto = { landed(it.projectId) },
        presentProjects = present,
        session = session,
        keyOf = { q -> ProjectBrowseSession.Key("mod", q, BrowseSort.Relevance, emptySet(), destination.pack.id, hiding = false) },
    )

    @Test
    fun `coming back to a search shows it as it was left and asks nothing`() = runTest {
        val clock = Clock()
        val session = ProjectBrowseSession { clock.now }
        val calls = mutableListOf<Pair<String, Int>>()
        var reads = 0
        val first = keyed(session, calls) { reads++; setOf("held") }
        first.search("j")
        first.more()
        first.rememberScroll(12, 40)

        // The screen is composed again on the way back from a project page.
        val again = keyed(session, calls) { reads++; emptySet() }
        again.search("j")

        assertEquals(listOf("j" to 0, "j" to 30), calls, "neither page is asked for again")
        assertEquals(1, reads, "nor what the pack holds")
        assertEquals(first.results, again.results)
        assertEquals(12 to 40, again.restoredScroll)
        assertTrue("held" in again.present)
    }

    @Test
    fun `a search left longer than the catalogue's pages are kept is asked again`() = runTest {
        val clock = Clock()
        val session = ProjectBrowseSession { clock.now }
        val calls = mutableListOf<Pair<String, Int>>()
        keyed(session, calls).search("j")

        clock.now += ProjectBrowseSession.FRESH_MS + 1
        keyed(session, calls).search("j")

        assertEquals(listOf("j" to 0, "j" to 0), calls)
    }

    @Test
    fun `another question is its own search`() = runTest {
        val session = ProjectBrowseSession { 0L }
        val calls = mutableListOf<Pair<String, Int>>()
        val state = keyed(session, calls)
        state.search("j")
        state.search("k")
        state.search("j")

        assertEquals(listOf("j" to 0, "k" to 0), calls, "going back to the first question shows it again")
    }

    @Test
    fun `an install from the list is kept with the search`() = runTest {
        val session = ProjectBrowseSession { 0L }
        val calls = mutableListOf<Pair<String, Int>>()
        val state = keyed(session, calls)
        state.search("j")
        state.install(hit("j3"))

        val again = keyed(session, calls)
        again.search("j")

        assertTrue("j3" in again.present, "the row reads installed on the way back too")
    }
}
