package hivens.ui.identity

import hivens.core.time.Clock
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ClanRoleProviderTest {

    // Trimmed from the live clan page: the roster block with the three role
    // shapes the site renders -- leader, deputy, and a plain member (empty
    // role span). UTF-8 fixture; the markup structure is the contract.
    private val rosterHtml = """
        <html><body>
        <div class="content-block np">
            <h1 class="green">Состав клана</h1>
            <div class="content-block-text">
                <div id="clan-members">
                    <div class="ban-item left">
                        <div class="ban-item-desc">
                            <h2><a href="player_Bladick" class="member-info">Bladick</a> <span class="member-role">Лидер</span></h2>
                            <h4>Личный KDR: 29.3</h4>
                        </div>
                    </div>
                    <div class="ban-item left">
                        <div class="ban-item-desc">
                            <h2><a href="player_Fana1ik1337" class="member-info">Fana1ik1337</a> <span class="member-role">Зам</span></h2>
                        </div>
                    </div>
                    <div class="ban-item left">
                        <div class="ban-item-desc">
                            <h2><a href="player_Rustovsky" class="member-info">Rustovsky</a> <span class="member-role"></span></h2>
                        </div>
                    </div>
                </div>
            </div>
        </div>
        </body></html>
    """.trimIndent()

    private class FakeClock(var now: Long = 0L) : Clock {
        override fun nowMillis(): Long = now
    }

    private fun provider(
        clock: FakeClock = FakeClock(),
        fetch: suspend (String) -> String? = { rosterHtml },
    ) = ClanRoleProvider(fetchPage = fetch, clock = clock)

    // ── parsing ──────────────────────────────────────────────────────────────

    @Test fun `the leader row parses as Leader`() {
        assertEquals(ClanRole.Leader, provider().parseRole(rosterHtml, "Bladick"))
    }

    @Test fun `a deputy is positively not the leader`() {
        assertEquals(ClanRole.NotLeader, provider().parseRole(rosterHtml, "Fana1ik1337"))
    }

    @Test fun `an empty role span is positively not the leader`() {
        assertEquals(ClanRole.NotLeader, provider().parseRole(rosterHtml, "Rustovsky"))
    }

    @Test fun `nick matching ignores case`() {
        assertEquals(ClanRole.Leader, provider().parseRole(rosterHtml, "bladick"))
    }

    @Test fun `a nick absent from the roster is Unknown, not NotLeader`() {
        assertEquals(ClanRole.Unknown, provider().parseRole(rosterHtml, "SomeoneElse"))
    }

    @Test fun `markup without the roster block is Unknown`() {
        assertEquals(ClanRole.Unknown, provider().parseRole("<html><body><p>maintenance</p></body></html>", "Bladick"))
        assertEquals(ClanRole.Unknown, provider().parseRole("", "Bladick"))
    }

    // ── fetch + cache ────────────────────────────────────────────────────────

    @Test fun `a failed fetch resolves to Unknown`() = runBlocking {
        assertEquals(ClanRole.Unknown, provider(fetch = { null }).role("Bladick", "ANIME"))
    }

    @Test fun `results cache per nick and tag within the TTL`() = runBlocking {
        var fetches = 0
        val clock = FakeClock()
        val p = provider(clock) { fetches++; rosterHtml }

        assertEquals(ClanRole.Leader, p.role("Bladick", "ANIME"))
        assertEquals(ClanRole.Leader, p.role("Bladick", "ANIME"))
        assertEquals(1, fetches, "second call within the TTL hits the cache")

        assertEquals(ClanRole.NotLeader, p.role("Rustovsky", "ANIME"))
        assertEquals(2, fetches, "a different nick is a different cache key")

        clock.now += 31 * 60 * 1000L
        p.role("Bladick", "ANIME")
        assertEquals(3, fetches, "the TTL expiry refetches")
    }
}
