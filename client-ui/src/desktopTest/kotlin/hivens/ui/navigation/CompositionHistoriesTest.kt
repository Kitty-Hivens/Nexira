package hivens.ui.navigation

import hivens.widget.model.CompositionName
import hivens.widget.model.Show
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What an interface that starts over finds of where it was. */
class CompositionHistoriesTest {

    private val histories = CompositionHistories()

    private fun launcher() = histories.of(LauncherConfigs.Composition, LauncherConfigs.Home)

    private fun CompositionHistory.configs() = now.entries.map { it.config }

    @Test
    fun `a composition drawn again goes on from where it stood`() {
        launcher().show(LauncherConfigs.pack("a"))
        assertSame(launcher(), launcher())
        assertEquals(listOf(LauncherConfigs.Home, LauncherConfigs.Pack), launcher().configs())
    }

    @Test
    fun `two compositions keep a history each`() {
        val other = histories.of(CompositionName("console"), LauncherConfigs.About)
        launcher().show(Show(LauncherConfigs.Library))
        assertNotSame(launcher(), other)
        assertEquals(listOf(LauncherConfigs.About), other.configs())
    }

    @Test
    fun `after a crash the page that was shown is one step forward`() {
        launcher().show(Show(LauncherConfigs.Library))
        launcher().show(LauncherConfigs.pack("a"))
        histories.afterCrash(again = false)
        assertEquals(LauncherConfigs.Library, launcher().now.current.config)
        launcher().forward()
        assertEquals(LauncherConfigs.Pack, launcher().now.current.config)
    }

    @Test
    fun `a crash again before the interface stayed up goes home, and keeps the way forward`() {
        launcher().show(Show(LauncherConfigs.Library))
        launcher().show(LauncherConfigs.pack("a"))
        launcher().show(LauncherConfigs.packVersions("a"))
        histories.afterCrash(again = false)
        assertEquals(LauncherConfigs.Pack, launcher().now.current.config)
        histories.afterCrash(again = true)
        assertEquals(listOf(LauncherConfigs.Home), launcher().configs())
        launcher().forward()
        assertEquals(LauncherConfigs.Library, launcher().now.current.config)
    }

    // One crash after another walks to the root instead of restarting on the same page until none is left.
    @Test
    fun `at its root a history stays there, and no back is counted against it`() {
        launcher().show(Show(LauncherConfigs.Library))
        repeat(3) { histories.afterCrash(again = false) }
        assertEquals(listOf(LauncherConfigs.Home), launcher().configs())
        assertEquals(0, launcher().now.backsAtRoot)
        assertTrue(launcher().now.canGoForward)
    }
}
