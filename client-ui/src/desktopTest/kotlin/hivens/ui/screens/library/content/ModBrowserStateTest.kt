package hivens.ui.screens.library.content

import hivens.core.api.dto.modrinth.ModrinthSearchHit
import hivens.launcher.instance.ModInstaller
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Installing a mod used to be a `runCatching` in a click lambda with the
 * success line outside it, so a download that threw still marked the row
 * Installed -- the user read "done" and the jar was not there. The state holder
 * makes the outcome a value the row can render.
 */
class ModBrowserStateTest {

    private fun hit(id: String) = ModrinthSearchHit(projectId = id, slug = id, title = id)

    /** An install that landed, reporting what the instance holds afterwards. */
    private fun landed(vararg present: String) =
        ModInstaller.Outcome(installed = listOf("mod.jar"), present = present.toSet())

    private val nothing = ModInstaller.Outcome()

    private fun state(
        search: suspend (String) -> List<ModrinthSearchHit> = { emptyList() },
        install: suspend (ModrinthSearchHit) -> ModInstaller.Outcome = { landed(it.projectId) },
        present: suspend () -> Set<String> = { emptySet() },
    ) = ModBrowserState(search = search, install = install, presentProjects = present)

    @Test
    fun `a successful install marks the project installed`() = runTest {
        val state = state()

        state.installMod(hit("jei"))

        assertEquals(setOf("jei"), state.installed)
        assertTrue(state.failed.isEmpty())
        assertTrue(state.working.isEmpty(), "the row must not stay spinning after the work ends")
    }

    @Test
    fun `a download that throws does not report success`() = runTest {
        val state = state(install = { throw IOException("connection reset") })

        state.installMod(hit("jei"))

        assertFalse("jei" in state.installed, "reporting an install that did not happen is the bug this replaced")
        assertEquals(setOf("jei"), state.failed)
        assertTrue(state.working.isEmpty())
    }

    @Test
    fun `a project with no build for this pack is a failure, not an install`() = runTest {
        val state = state(install = { nothing })

        state.installMod(hit("jei"))

        assertFalse("jei" in state.installed)
        assertEquals(setOf("jei"), state.failed)
    }

    @Test
    fun `retrying clears the previous failure`() = runTest {
        var succeed = false
        val state = state(install = { if (succeed) landed(it.projectId) else nothing })

        state.installMod(hit("jei"))
        assertEquals(setOf("jei"), state.failed)

        succeed = true
        state.installMod(hit("jei"))

        assertEquals(setOf("jei"), state.installed)
        assertTrue(state.failed.isEmpty(), "a row that succeeded on retry must stop reading as failed")
    }

    @Test
    fun `a failed search shows an empty result rather than a spinner forever`() = runTest {
        val state = state(search = { throw IOException("offline") })

        state.runSearch("jei")

        assertEquals(emptyList(), state.results, "null is the in-flight state; a dead search must leave it")
    }

    @Test
    fun `the folder decides what reads as installed, not this session`() = runTest {
        val state = state(present = { setOf("sodium", "iris", "jei") })

        state.loadInstalled()

        assertEquals(setOf("sodium", "iris", "jei"), state.installed,
            "a pack of ninety mods must not be offered its own contents to install")
    }

    @Test
    fun `a dependency pulled in behind an install stops offering itself`() = runTest {
        // Installing Iris drags Sodium along, and Sodium has its own row in the
        // same result list.
        val state = state(install = { landed("iris", "sodium") })

        state.installMod(hit("iris"))

        assertTrue("sodium" in state.installed, "the dependency landed, so its row is not an install")
        assertTrue("iris" in state.installed)
    }

    @Test
    fun `a folder that cannot be read leaves the browser usable`() = runTest {
        val state = state(present = { throw IOException("offline") })

        state.loadInstalled()

        assertTrue(state.installed.isEmpty(), "unknown is not installed")
    }

    @Test
    fun `search results reach the browser`() = runTest {
        val state = state(search = { listOf(hit("jei"), hit("journeymap")) })

        state.runSearch("j")

        assertEquals(listOf("jei", "journeymap"), state.results?.map { it.projectId })
    }
}
