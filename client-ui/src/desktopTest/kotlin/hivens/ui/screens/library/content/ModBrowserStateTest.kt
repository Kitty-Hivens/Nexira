package hivens.ui.screens.library.content

import hivens.core.api.dto.modrinth.ModrinthSearchHit
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

    @Test
    fun `a successful install marks the project installed`() = runTest {
        val state = ModBrowserState(search = { emptyList() }, install = { true })

        state.installMod(hit("jei"))

        assertEquals(setOf("jei"), state.installed)
        assertTrue(state.failed.isEmpty())
        assertTrue(state.working.isEmpty(), "the row must not stay spinning after the work ends")
    }

    @Test
    fun `a download that throws does not report success`() = runTest {
        val state = ModBrowserState(search = { emptyList() }, install = { throw IOException("connection reset") })

        state.installMod(hit("jei"))

        assertFalse("jei" in state.installed, "reporting an install that did not happen is the bug this replaced")
        assertEquals(setOf("jei"), state.failed)
        assertTrue(state.working.isEmpty())
    }

    @Test
    fun `a project with no build for this pack is a failure, not an install`() = runTest {
        val state = ModBrowserState(search = { emptyList() }, install = { false })

        state.installMod(hit("jei"))

        assertFalse("jei" in state.installed)
        assertEquals(setOf("jei"), state.failed)
    }

    @Test
    fun `retrying clears the previous failure`() = runTest {
        var succeed = false
        val state = ModBrowserState(search = { emptyList() }, install = { succeed })

        state.installMod(hit("jei"))
        assertEquals(setOf("jei"), state.failed)

        succeed = true
        state.installMod(hit("jei"))

        assertEquals(setOf("jei"), state.installed)
        assertTrue(state.failed.isEmpty(), "a row that succeeded on retry must stop reading as failed")
    }

    @Test
    fun `a failed search shows an empty result rather than a spinner forever`() = runTest {
        val state = ModBrowserState(search = { throw IOException("offline") }, install = { true })

        state.runSearch("jei")

        assertEquals(emptyList(), state.results, "null is the in-flight state; a dead search must leave it")
    }

    @Test
    fun `search results reach the browser`() = runTest {
        val state = ModBrowserState(search = { listOf(hit("jei"), hit("journeymap")) }, install = { true })

        state.runSearch("j")

        assertEquals(listOf("jei", "journeymap"), state.results?.map { it.projectId })
    }
}
