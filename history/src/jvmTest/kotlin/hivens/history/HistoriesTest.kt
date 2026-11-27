package hivens.history

import hivens.pages.api.Show
import hivens.widget.api.InterfaceRestarts
import hivens.widget.model.CompositionName
import hivens.widget.model.ConfigId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What an interface that starts over finds of where it was. */
class HistoriesTest {

    private val histories = Histories()

    private val main = CompositionName("main")
    private val home = ConfigId("home")
    private val library = ConfigId("library")
    private val pack = ConfigId("pack")
    private val versions = ConfigId("pack.versions")

    private fun main(restarts: InterfaceRestarts? = null) = histories.of(main, home, restarts)

    private fun CompositionHistory.configs() = now.value.entries.map { it.config }

    @Test
    fun `a composition drawn again goes on from where it stood`() {
        main().show(Show(pack))
        assertSame(main(), main())
        assertEquals(listOf(home, pack), main().configs())
    }

    @Test
    fun `two compositions keep a history each`() {
        val other = histories.of(CompositionName("console"), ConfigId("console"))
        main().show(Show(library))
        assertNotSame(main(), other)
        assertEquals(listOf(ConfigId("console")), other.configs())
    }

    @Test
    fun `a config the composition does not have is not shown`() {
        main().show(Show(library)) { it != library }
        assertEquals(listOf(home), main().configs())
    }

    @Test
    fun `after a crash the page that was shown is one step forward`() {
        main().show(Show(library))
        main().show(Show(pack))
        histories.afterCrash(again = false)
        assertEquals(library, main().now.value.current.config)
        main().forward()
        assertEquals(pack, main().now.value.current.config)
    }

    @Test
    fun `a crash again before the interface stayed up goes home, and keeps the way forward`() {
        main().show(Show(library))
        main().show(Show(pack))
        main().show(Show(versions))
        histories.afterCrash(again = false)
        assertEquals(pack, main().now.value.current.config)
        histories.afterCrash(again = true)
        assertEquals(listOf(home), main().configs())
        main().forward()
        assertEquals(library, main().now.value.current.config)
    }

    // One crash after another walks to the root instead of restarting on the same page until none is left.
    @Test
    fun `at its root a history stays there, and no back is counted against it`() {
        main().show(Show(library))
        repeat(3) { histories.afterCrash(again = false) }
        assertEquals(listOf(home), main().configs())
        assertEquals(0, main().now.value.backsAtRoot)
        assertTrue(main().now.value.canGoForward)
    }

    @Test
    fun `a start over the host counted steps back once, when the history is next asked for`() {
        main().show(Show(library))
        main().show(Show(pack))
        val once = InterfaceRestarts(count = 1, again = false)
        assertEquals(library, main(once).now.value.current.config)
        assertEquals(library, main(once).now.value.current.config, "the same start over is not stepped back from twice")
        assertEquals(listOf(home), main(InterfaceRestarts(count = 2, again = true)).configs())
    }
}
