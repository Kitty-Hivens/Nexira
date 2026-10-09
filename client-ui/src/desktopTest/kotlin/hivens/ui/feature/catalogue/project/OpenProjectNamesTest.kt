package hivens.ui.feature.catalogue.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which page the rail describes, and the names the trail keeps for pages left behind. */
class OpenProjectNamesTest {

    private fun project(key: String, title: String) =
        OpenProject(targetKey = key, title = title, slug = key, source = ProjectSource.Catalogue)

    @Test
    fun `a page's name outlives the page`() {
        val state = OpenProjectState()
        val page = Any()
        state.claim(page)
        state.publish(page, project("catalogue:YL57xq9U:", "Iris Shaders"))
        state.name("catalogue:YL57xq9U:", "Iris Shaders")
        state.release(page)

        assertNull(state.open.value)
        assertEquals("Iris Shaders", state.names.value["catalogue:YL57xq9U:"])
    }

    @Test
    fun `a page on its way out neither writes over the arriving one nor clears it`() {
        // The shell fades between pages, so the leaving one is still mounted, and
        // still loading, after the arriving one has claimed the rail.
        val state = OpenProjectState()
        val leaving = Any()
        val arriving = Any()
        state.claim(leaving)
        state.publish(leaving, project("a", "Sodium"))
        state.claim(arriving)
        state.publish(arriving, project("b", "Iris"))

        state.publish(leaving, project("a", "Sodium, late"))
        state.release(leaving)

        assertEquals("Iris", state.open.value?.title)
    }

    /**
     * Back inside the fade keeps the page it returns to, so that page never claims
     * again. The one leaving used to take the rail down with it and leave the page
     * on screen beside an empty rail.
     */
    @Test
    fun `a page leaving over the one under it gives the rail back to that one`() {
        val state = OpenProjectState()
        val under = Any()
        val over = Any()
        state.claim(under)
        state.publish(under, project("a", "Sodium"))
        state.claim(over)
        state.publish(over, project("b", "Iris"))
        // Published while the other page held the rail: kept, not shown.
        state.publish(under, project("a", "Sodium, loaded"))
        assertEquals("Iris", state.open.value?.title)

        state.release(over)

        assertEquals("Sodium, loaded", state.open.value?.title)
        state.release(under)
        assertNull(state.open.value)
    }

    @Test
    fun `two visits to one project are two owners`() {
        val state = OpenProjectState()
        val first = Any()
        val second = Any()
        state.claim(first)
        state.claim(second)
        state.publish(second, project("a", "Sodium"))

        state.release(first)

        assertEquals("Sodium", state.open.value?.title, "the same key is not the same page")
    }

    @Test
    fun `the oldest names go first once the bound is reached`() {
        val state = OpenProjectState()
        repeat(70) { state.name("k$it", "Project $it") }
        // Seen again, so it counts as the newest and is kept.
        state.name("k0", "Project 0")
        state.name("k70", "Project 70")

        val names = state.names.value
        assertEquals(64, names.size)
        assertEquals("Project 0", names["k0"])
        assertNull(names["k7"], "the oldest not seen again is gone")
        assertEquals("Project 70", names["k70"])
    }
}
