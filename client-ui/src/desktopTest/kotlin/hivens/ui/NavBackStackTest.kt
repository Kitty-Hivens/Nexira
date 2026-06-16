package hivens.ui

import hivens.core.api.model.ServerProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavBackStackTest {

    @Test
    fun startsAtRootWithNothingToPop() {
        val nav = NavBackStack(Screen.Home)
        assertEquals(Screen.Home, nav.current)
        assertFalse(nav.canGoBack)
        assertFalse(nav.back())
        assertEquals(Screen.Home, nav.current)
    }

    @Test
    fun detailScreenPushesAndBackPops() {
        val nav = NavBackStack(Screen.Library)
        nav.navigate(Screen.PackDetail("abc"))
        assertEquals(Screen.PackDetail("abc"), nav.current)
        assertTrue(nav.canGoBack)
        assertTrue(nav.back())
        assertEquals(Screen.Library, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun backUnwindsDeepFlowToOrigin() {
        // The exact scenario the issue calls out: Browse -> pack detail ->
        // install -> installed detail should back out the way it came in, not
        // jump to a fixed Library literal.
        val nav = NavBackStack(Screen.Browse)
        nav.navigate(Screen.BrowsePackDetail("Industrial"))
        nav.navigate(Screen.PackDetail("inst-1"))
        assertEquals(Screen.PackDetail("inst-1"), nav.current)
        nav.back()
        assertEquals(Screen.BrowsePackDetail("Industrial"), nav.current)
        nav.back()
        assertEquals(Screen.Browse, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun topLevelDestinationResetsHistory() {
        val nav = NavBackStack(Screen.Home)
        nav.navigate(Screen.ServerSettings(ServerProfile()))
        assertTrue(nav.canGoBack)
        nav.navigate(Screen.Library)
        assertEquals(Screen.Library, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun reSelectingCurrentScreenIsNoOp() {
        val nav = NavBackStack(Screen.Home)
        nav.navigate(Screen.Home)
        assertFalse(nav.canGoBack)

        nav.navigate(Screen.Library)
        nav.navigate(Screen.Library)
        assertEquals(Screen.Library, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun tabHoppingNeverGrowsHistory() {
        val nav = NavBackStack(Screen.Home)
        nav.navigate(Screen.Library)
        nav.navigate(Screen.Browse)
        nav.navigate(Screen.Settings)
        nav.navigate(Screen.Profile)
        assertEquals(Screen.Profile, nav.current)
        assertFalse(nav.canGoBack)
    }
}
