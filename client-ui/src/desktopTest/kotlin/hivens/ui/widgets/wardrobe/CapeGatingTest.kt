package hivens.ui.widgets.wardrobe

import hivens.core.data.SessionData
import hivens.ui.identity.ClanRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapeGatingTest {

    private fun sc(clan: String?, resolved: Boolean) =
        SessionData(playerName = "Player", clan = clan, clanResolved = resolved)

    @Test fun `no SmartyCraft account hides the cape block`() {
        assertFalse(capeSectionVisible(null, ClanRole.Unknown))
    }

    @Test fun `a login that resolved to no clan hides it`() {
        assertFalse(capeSectionVisible(sc(clan = null, resolved = true), ClanRole.Unknown))
    }

    @Test fun `a roster-confirmed non-leader hides it`() {
        assertFalse(capeSectionVisible(sc(clan = "ANIME", resolved = true), ClanRole.NotLeader))
    }

    @Test fun `the leader sees it`() {
        assertTrue(capeSectionVisible(sc(clan = "ANIME", resolved = true), ClanRole.Leader))
    }

    @Test fun `an unknown role fails open`() {
        assertTrue(capeSectionVisible(sc(clan = "ANIME", resolved = true), ClanRole.Unknown))
    }

    @Test fun `a pre-field persisted session fails open`() {
        // Old credentials decode clanResolved = false regardless of the real
        // clan state -- capability unknown, so the block must stay visible.
        assertTrue(capeSectionVisible(sc(clan = null, resolved = false), ClanRole.Unknown))
    }
}
