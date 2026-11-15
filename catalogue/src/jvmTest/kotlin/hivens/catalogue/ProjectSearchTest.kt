package hivens.catalogue

import hivens.catalogue.api.BrowseSort
import hivens.catalogue.api.InstallProblem
import hivens.catalogue.api.InstallRefusal
import hivens.catalogue.api.ProjectHit
import hivens.catalogue.api.ProjectKind
import hivens.catalogue.api.ScrollMark
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.launch.InstanceWork
import hivens.launcher.instance.ContentDestination
import hivens.launcher.instance.ContentInstaller
import hivens.launcher.instance.ModInstaller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Installing a mod used to be a `runCatching` in a click lambda with the success
 * line outside it, so a download that threw still marked the row Installed: the
 * person read "done" and the jar was not there. The search makes the outcome a value
 * the row can render, and says why a row failed.
 */
class ProjectSearchTest {

    private fun hit(id: String) = ProjectHit(projectId = id, slug = id, title = id)

    /** An install that landed, reporting what the instance holds afterwards. */
    private fun landed(vararg present: String) =
        ModInstaller.Outcome(installed = listOf("mod.jar"), present = present.toSet())

    private val nothing = ModInstaller.Outcome()

    private val destination = ContentDestination.Pack.of(
        PackInstance(id = "p", packRef = PackReference(PackOrigin.Local, "p"), displayName = "Probe", instanceDirName = "probe", createdAtEpoch = 0L),
        Path.of("/tmp/nexira-test-data/instances"),
    )

    private fun state(
        search: suspend (String, Int, Int, Collection<String>) -> List<ProjectHit> = { _, _, _, _ -> emptyList() },
        install: suspend (String) -> ModInstaller.Outcome? = { landed(it) },
        present: suspend () -> Set<String> = { emptySet() },
        hideInstalled: Boolean = false,
    ) = ProjectSearch(ProjectKind.Mod, destination, search, install, present, hideInstalled)

    @Test
    fun `a successful install marks the project installed`() = runTest {
        val state = state()

        state.install("jei")

        assertEquals(setOf("jei"), state.present)
        assertTrue(state.problems.isEmpty())
        assertTrue(state.working.isEmpty(), "the row must not stay spinning after the work ends")
    }

    /** The build is in, and the game will refuse it until what it needs is: the card has to say so. */
    @Test
    fun `an install that landed without a required dependency says how many it went without`() = runTest {
        val state = state(install = { id ->
            landed(id).let { if (id == "ars") it.copy(missing = listOf("curios", "patchouli")) else it }
        })

        state.install("ars")

        assertEquals(setOf("ars"), state.present, "the build itself landed")
        assertEquals(mapOf("ars" to 2), state.missing)
        assertTrue(state.problems.isEmpty(), "it is not a failed install, the action is not offered again")

        state.install("jei")
        assertEquals(null, state.missing["jei"])
    }

    @Test
    fun `a download that throws does not report success`() = runTest {
        val state = state(install = { throw IOException("connection reset") })

        state.install("jei")

        assertFalse("jei" in state.present, "reporting an install that did not happen is the bug this replaced")
        assertEquals(mapOf("jei" to InstallProblem.NotLanded), state.problems)
        assertTrue(state.working.isEmpty())
    }

    @Test
    fun `a project with no build for this pack says so`() = runTest {
        val state = state(install = { null })

        state.install("jei")

        assertFalse("jei" in state.present)
        assertEquals(mapOf("jei" to InstallProblem.NoBuild), state.problems)
    }

    @Test
    fun `an install the pack refused says why`() = runTest {
        val state = state(install = { ModInstaller.Outcome(refusal = ContentInstaller.Refusal.Busy(InstanceWork.Update)) })

        state.install("jei")

        assertEquals(mapOf("jei" to InstallProblem.Refused(InstallRefusal.Busy)), state.problems)
    }

    @Test
    fun `retrying clears the previous failure`() = runTest {
        var succeed = false
        val state = state(install = { if (succeed) landed(it) else nothing })

        state.install("jei")
        assertEquals(mapOf("jei" to InstallProblem.NotLanded), state.problems)

        succeed = true
        state.install("jei")

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

        state.install("iris")

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
            install = { landed("sodium", it) },
            hideInstalled = true,
        )

        state.search("")
        state.install("p3")
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

    // A search kept for the session.

    private class Clock(var now: Long = 0L)

    private fun keyed(
        session: ProjectSnapshots,
        calls: MutableList<Pair<String, Int>>,
        stamp: () -> String? = { null },
        present: suspend () -> Set<String> = { emptySet() },
    ) = ProjectSearch(
        ProjectKind.Mod,
        destination,
        search = { q, offset, _, _ -> calls += q to offset; if (offset == 0) (1..30).map { hit("$q$it") } else listOf(hit("${q}x")) },
        installInto = { landed(it) },
        presentProjects = present,
        session = session,
        keyOf = { q -> ProjectSnapshots.Key("mod", q, BrowseSort.Relevance, emptySet(), destination.instanceId, hiding = false) },
        contentStamp = { stamp() },
    )

    @Test
    fun `coming back to a search shows it as it was left and asks nothing`() = runTest {
        val clock = Clock()
        val session = ProjectSnapshots { clock.now }
        val calls = mutableListOf<Pair<String, Int>>()
        var reads = 0
        val first = keyed(session, calls) { reads++; setOf("held") }
        first.search("j")
        first.more()
        first.rememberScroll("j", ScrollMark(12, 40))

        // The screen is composed again on the way back from a project page.
        val again = keyed(session, calls) { reads++; emptySet() }
        again.search("j")

        assertEquals(listOf("j" to 0, "j" to 30), calls, "neither page is asked for again")
        assertEquals(1, reads, "nor what the pack holds")
        assertEquals(first.results, again.results)
        assertEquals(ScrollMark(12, 40), again.restoredScroll)
        assertTrue("held" in again.present)
    }

    @Test
    fun `a search left longer than the catalogue's pages are kept is asked again`() = runTest {
        val clock = Clock()
        val session = ProjectSnapshots { clock.now }
        val calls = mutableListOf<Pair<String, Int>>()
        keyed(session, calls).search("j")

        clock.now += ProjectSnapshots.FRESH_MS + 1
        keyed(session, calls).search("j")

        assertEquals(listOf("j" to 0, "j" to 0), calls)
    }

    @Test
    fun `another question is its own search`() = runTest {
        val session = ProjectSnapshots { 0L }
        val calls = mutableListOf<Pair<String, Int>>()
        val state = keyed(session, calls)
        state.search("j")
        state.search("k")
        state.search("j")

        assertEquals(listOf("j" to 0, "k" to 0), calls, "going back to the first question shows it again")
    }

    @Test
    fun `an install from the list is kept with the search`() = runTest {
        val session = ProjectSnapshots { 0L }
        val calls = mutableListOf<Pair<String, Int>>()
        val state = keyed(session, calls)
        state.search("j")
        state.install("j3")

        val again = keyed(session, calls)
        again.search("j")

        assertTrue("j3" in again.present, "the row reads installed on the way back too")
    }

    /**
     * Typing on cancels the search before it, and the pack was marked read before
     * the read began: cancelled there, the pack stayed unread for the rest of the
     * search, installed projects offering Install and hiding hiding nothing.
     */
    @Test
    fun `a search cancelled while it read the pack reads it on the next one`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val state = state(present = { reads++; if (reads == 1) gate.await(); setOf("held") })

        val first = launch { state.search("a") }
        yield()
        first.cancel()
        state.search("ab")

        assertEquals(2, reads)
        assertTrue("held" in state.present)
    }

    /** A mod installed from a project page, or removed in the Content tab, while the search was kept. */
    @Test
    fun `a kept search reads the pack again when its folders changed, and only then`() = runTest {
        val session = ProjectSnapshots { 0L }
        val calls = mutableListOf<Pair<String, Int>>()
        var stamp = "before"
        keyed(session, calls, stamp = { stamp }) { setOf("removed-since") }.search("j")

        val unchanged = keyed(session, calls, stamp = { stamp }) { error("the folders did not change") }
        unchanged.search("j")
        assertTrue("removed-since" in unchanged.present)

        stamp = "after"
        val changed = keyed(session, calls, stamp = { stamp }) { setOf("installed-since") }
        changed.search("j")

        assertEquals(setOf("installed-since"), changed.present)
        assertEquals(listOf("j" to 0), calls, "the catalogue is still not asked again")
    }

    /**
     * The window keeps one search for as long as it is open, and a return to Browse asks
     * it again. Once the kept answer has gone stale the search is asked anew, and what
     * the pack holds was taken from before: a mod removed in the Content tab read
     * installed for the life of the window.
     */
    @Test
    fun `a search asked again once its answer went stale reads the pack again when its folders changed`() = runTest {
        val clock = Clock()
        val session = ProjectSnapshots { clock.now }
        val calls = mutableListOf<Pair<String, Int>>()
        var stamp = "before"
        var held = setOf("removed-since")
        val state = keyed(session, calls, stamp = { stamp }) { held }
        state.search("j")
        assertEquals(setOf("removed-since"), state.present)

        stamp = "after"
        held = emptySet()
        clock.now += ProjectSnapshots.FRESH_MS + 1
        state.search("j")

        assertEquals(listOf("j" to 0, "j" to 0), calls, "the stale answer is asked for again")
        assertTrue(state.present.isEmpty(), "and the pack is read again with it: ${state.present}")
    }

    /** An install started here runs on after the reader left, and its finish used to write this search's list back. */
    @Test
    fun `a state left behind cannot write over the search the screen came back to`() = runTest {
        val clock = Clock()
        val session = ProjectSnapshots { clock.now }
        val calls = mutableListOf<Pair<String, Int>>()
        val left = keyed(session, calls)
        left.search("j")

        clock.now = 10
        val back = keyed(session, calls)
        back.search("j")
        back.more()

        clock.now = 20
        left.install("j3")

        val key = ProjectSnapshots.Key("mod", "j", BrowseSort.Relevance, emptySet(), destination.instanceId, hiding = false)
        assertEquals(31, session.get(key)?.results?.size, "the longer list the screen came back to is kept")
    }

    /** The window keeps the search, so a list drawn on the way back starts from what the search says. */
    @Test
    fun `the answer shown starts where it was left the next time it is drawn`() = runTest {
        val session = ProjectSnapshots { 0L }
        val calls = mutableListOf<Pair<String, Int>>()
        var changes = 0
        val state = ProjectSearch(
            ProjectKind.Mod,
            destination,
            search = { q, _, _, _ -> calls += q to 0; listOf(hit("${q}1")) },
            installInto = { landed(it) },
            presentProjects = { emptySet() },
            session = session,
            keyOf = { q -> ProjectSnapshots.Key("mod", q, BrowseSort.Relevance, emptySet(), destination.instanceId, hiding = false) },
            changed = { changes++ },
        )
        state.search("j")
        val before = changes

        state.rememberScroll("j", ScrollMark(12, 40))

        assertEquals(ScrollMark(12, 40), state.restoredScroll)
        assertTrue(changes > before, "and says so, for the results to carry it")
        state.rememberScroll("k", ScrollMark(3, 0))
        assertEquals(ScrollMark(12, 40), state.restoredScroll, "a question not shown keeps its place to itself")
    }

    @Test
    fun `a new question starts at the top, whatever the last one was scrolled to`() = runTest {
        val session = ProjectSnapshots { 0L }
        val calls = mutableListOf<Pair<String, Int>>()
        val first = keyed(session, calls)
        first.search("j")
        first.rememberScroll("j", ScrollMark(12, 40))

        val again = keyed(session, calls)
        again.search("j")
        assertEquals(ScrollMark(12, 40), again.restoredScroll)
        again.search("k")

        assertEquals(ScrollMark(), again.restoredScroll)
        assertEquals("k", again.shownQuery)
    }
}
