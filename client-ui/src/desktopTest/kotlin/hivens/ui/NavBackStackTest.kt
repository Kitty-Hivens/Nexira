package hivens.ui

import hivens.core.api.model.ServerProfile
import hivens.core.data.PackOrigin
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
    fun versionsScreenPushesOverDetailAndBacksOut() {
        val nav = NavBackStack(Screen.Library)
        nav.navigate(Screen.PackDetail("abc"))
        nav.navigate(Screen.PackVersions("abc"))
        assertEquals(Screen.PackVersions("abc"), nav.current)
        assertTrue(nav.back())
        assertEquals(Screen.PackDetail("abc"), nav.current)
        assertTrue(nav.back())
        assertEquals(Screen.Library, nav.current)
    }

    @Test
    fun replaceCurrentRestampsTheEntryBackReturnsTo() {
        // The settings-overlay drill-down: PackDetail marks itself openSettings
        // before pushing the versions screen, so Back restores the overlay.
        val nav = NavBackStack(Screen.Library)
        nav.navigate(Screen.PackDetail("abc"))
        nav.replaceCurrent(Screen.PackDetail("abc", openSettings = true))
        nav.navigate(Screen.PackVersions("abc"))
        assertTrue(nav.back())
        assertEquals(Screen.PackDetail("abc", openSettings = true), nav.current)
        assertTrue(nav.back())
        assertEquals(Screen.Library, nav.current)
    }

    @Test
    fun backUnwindsDeepFlowToOrigin() {
        // The exact scenario the issue calls out: Browse -> pack detail ->
        // install -> installed detail should back out the way it came in, not
        // jump to a fixed Library literal.
        val nav = NavBackStack(Screen.Browse)
        nav.navigate(Screen.CataloguePackDetail(PackOrigin.Mirror, "Industrial"))
        nav.navigate(Screen.PackDetail("inst-1"))
        assertEquals(Screen.PackDetail("inst-1"), nav.current)
        nav.back()
        assertEquals(Screen.CataloguePackDetail(PackOrigin.Mirror, "Industrial"), nav.current)
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
    fun trailReflectsStackOrder() {
        val nav = NavBackStack(Screen.Browse)
        nav.navigate(Screen.CataloguePackDetail(PackOrigin.Mirror, "Industrial"))
        nav.navigate(Screen.PackDetail("inst-1"))
        assertEquals(
            listOf(Screen.Browse, Screen.CataloguePackDetail(PackOrigin.Mirror, "Industrial"), Screen.PackDetail("inst-1")),
            nav.trail,
        )
    }

    @Test
    fun trailIsAReadOnlySnapshot() {
        val nav = NavBackStack(Screen.Home)
        val snap = nav.trail
        nav.navigate(Screen.Library)
        // The earlier snapshot must not observe the later push.
        assertEquals(listOf(Screen.Home), snap)
        assertEquals(listOf(Screen.Library), nav.trail)
    }

    @Test
    fun popToUnwindsToNamedSegment() {
        val nav = NavBackStack(Screen.Browse)
        nav.navigate(Screen.CataloguePackDetail(PackOrigin.Mirror, "Industrial"))
        nav.navigate(Screen.PackDetail("inst-1"))
        nav.popTo(Screen.Browse)
        assertEquals(Screen.Browse, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun popToIsNoOpForCurrentOrAbsent() {
        val nav = NavBackStack(Screen.Library)
        nav.navigate(Screen.PackDetail("x"))
        nav.popTo(Screen.PackDetail("x")) // current -> no-op
        assertEquals(Screen.PackDetail("x"), nav.current)
        nav.popTo(Screen.Browse)          // absent -> no-op
        assertEquals(Screen.PackDetail("x"), nav.current)
        assertTrue(nav.canGoBack)
    }

    @Test
    fun forwardReappliesBackedScreen() {
        val nav = NavBackStack(Screen.Browse)
        nav.navigate(Screen.CataloguePackDetail(PackOrigin.Mirror, "X"))
        assertTrue(nav.back())
        assertEquals(Screen.Browse, nav.current)
        assertTrue(nav.canGoForward)
        assertTrue(nav.forward())
        assertEquals(Screen.CataloguePackDetail(PackOrigin.Mirror, "X"), nav.current)
        assertFalse(nav.canGoForward)
    }

    @Test
    fun navigateClearsForward() {
        val nav = NavBackStack(Screen.Browse)
        nav.navigate(Screen.CataloguePackDetail(PackOrigin.Mirror, "X"))
        nav.back()
        assertTrue(nav.canGoForward)
        nav.navigate(Screen.CataloguePackDetail(PackOrigin.Mirror, "Y")) // new branch -> forward dropped
        assertFalse(nav.canGoForward)
        assertFalse(nav.forward())
    }

    @Test
    fun forwardIsNoOpWhenEmpty() {
        val nav = NavBackStack(Screen.Home)
        assertFalse(nav.canGoForward)
        assertFalse(nav.forward())
    }

    @Test
    fun popToFillsForwardAndReExpandsInOrder() {
        val nav = NavBackStack(Screen.Browse)
        nav.navigate(Screen.CataloguePackDetail(PackOrigin.Mirror, "X"))
        nav.navigate(Screen.PackDetail("i1"))
        nav.popTo(Screen.Browse)
        assertEquals(Screen.Browse, nav.current)
        assertTrue(nav.canGoForward)
        nav.forward()
        assertEquals(Screen.CataloguePackDetail(PackOrigin.Mirror, "X"), nav.current)
        nav.forward()
        assertEquals(Screen.PackDetail("i1"), nav.current)
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

    @Test
    fun navRailSiblingsResetInsteadOfStacking() {
        // Wardrobe and About are nav-rail siblings: hopping among them must reset,
        // not pile up a "Profile > Wardrobe > About > Wardrobe" breadcrumb.
        val nav = NavBackStack(Screen.Profile)
        nav.navigate(Screen.Wardrobe)
        nav.navigate(Screen.About)
        nav.navigate(Screen.Wardrobe)
        nav.navigate(Screen.About)
        assertEquals(Screen.About, nav.current)
        assertEquals(listOf(Screen.About), nav.trail)
        assertFalse(nav.canGoBack)
    }
}
