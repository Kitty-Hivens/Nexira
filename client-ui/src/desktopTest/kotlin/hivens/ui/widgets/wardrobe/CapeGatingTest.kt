package hivens.ui.widgets.wardrobe

import hivens.ui.identity.ClanRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapeGatingTest {

    @Test fun `no SmartyCraft account hides the cape block`() {
        assertFalse(capeSectionVisible(hasScAccount = false, role = ClanRole.Unknown))
        assertFalse(capeSectionVisible(hasScAccount = false, role = ClanRole.Leader))
    }

    @Test fun `a positively clan-less profile hides it`() {
        assertFalse(capeSectionVisible(hasScAccount = true, role = ClanRole.NoClan))
    }

    @Test fun `a confirmed non-leader hides it`() {
        assertFalse(capeSectionVisible(hasScAccount = true, role = ClanRole.NotLeader))
    }

    @Test fun `the leader sees it`() {
        assertTrue(capeSectionVisible(hasScAccount = true, role = ClanRole.Leader))
    }

    @Test fun `an unknown standing fails open`() {
        // Page down / markup drift / pre-field session with no answer yet --
        // the block stays visible with the clan hint rather than silently
        // locking a legitimate leader out.
        assertTrue(capeSectionVisible(hasScAccount = true, role = ClanRole.Unknown))
    }
}
