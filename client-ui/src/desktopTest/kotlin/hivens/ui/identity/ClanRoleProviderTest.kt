package hivens.ui.identity

import hivens.core.time.Clock
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ClanRoleProviderTest {

    // Trimmed from live player pages: the profile block with the stats
    // button (the page-validity marker) and the clan heading in its three
    // shapes -- leader, member, absent. UTF-8 fixtures; the markup structure
    // is the contract.
    private fun profileHtml(nick: String, clanHeading: String?) = """
        <html><body>
        <div class="content-block">
            <div class="form-wide">
                <div class="label"><a href="im_$nick" class="button b-green block">Личное сообщение</a></div>
                <div class="val"><a href="stats_player_$nick" class="button b-green block">Статистика игры</a></div>
            </div>
            ${clanHeading?.let { "<h2>$it</h2><h3>Вступил в клан 25 августа 2019 г.</h3>" } ?: ""}
        </div>
        </body></html>
    """.trimIndent()

    private val leaderClan = """Лидер клана <a href="clan_ANIME" style="color:#FF55FF">Animeshniki</a>"""
    private val memberClan = """Участник клана <a href="clan_ANIME" style="color:#FF55FF">Animeshniki</a>"""

    private class FakeClock(var now: Long = 0L) : Clock {
        override fun nowMillis(): Long = now
    }

    private fun provider(
        clock: FakeClock = FakeClock(),
        fetch: suspend (String) -> String? = { null },
    ) = ClanRoleProvider(fetchPage = fetch, clock = clock)

    // ── parsing ──────────────────────────────────────────────────────────────

    @Test fun `a leader heading parses as Leader`() {
        assertEquals(ClanRole.Leader, provider().parseEligibility(profileHtml("Bladick", leaderClan), "Bladick"))
    }

    @Test fun `a member heading is positively not the leader`() {
        assertEquals(ClanRole.NotLeader, provider().parseEligibility(profileHtml("Rustovsky", memberClan), "Rustovsky"))
    }

    @Test fun `a profile without a clan link is positively clan-less`() {
        assertEquals(ClanRole.NoClan, provider().parseEligibility(profileHtml("NoLikeHumans", null), "NoLikeHumans"))
    }

    @Test fun `the validity marker matches the nick case-insensitively`() {
        assertEquals(ClanRole.NoClan, provider().parseEligibility(profileHtml("NoLikeHumans", null), "nolikehumans"))
    }

    @Test fun `a page without the stats marker is Unknown, never NoClan`() {
        // A 404 / maintenance / wrong-page response has no clan link either;
        // without the profile marker it must not read as a positive answer.
        assertEquals(ClanRole.Unknown, provider().parseEligibility("<html><body><p>maintenance</p></body></html>", "Bladick"))
        assertEquals(ClanRole.Unknown, provider().parseEligibility("", "Bladick"))
        // Someone ELSE's profile (marker for a different nick) is not ours.
        assertEquals(ClanRole.Unknown, provider().parseEligibility(profileHtml("OtherGuy", null), "Bladick"))
    }

    // ── fetch + cache ────────────────────────────────────────────────────────

    @Test fun `a failed fetch resolves to Unknown`() = runBlocking {
        assertEquals(ClanRole.Unknown, provider(fetch = { null }).eligibility("Bladick"))
    }

    @Test fun `results cache per nick within the TTL`() = runBlocking {
        var fetches = 0
        val clock = FakeClock()
        val p = provider(clock) { url ->
            fetches++
            when {
                url.endsWith("Bladick") -> profileHtml("Bladick", leaderClan)
                else -> profileHtml("NoLikeHumans", null)
            }
        }

        assertEquals(ClanRole.Leader, p.eligibility("Bladick"))
        assertEquals(ClanRole.Leader, p.eligibility("Bladick"))
        assertEquals(1, fetches, "second call within the TTL hits the cache")

        assertEquals(ClanRole.NoClan, p.eligibility("NoLikeHumans"))
        assertEquals(2, fetches, "a different nick is a different cache key")

        clock.now += 31 * 60 * 1000L
        p.eligibility("Bladick")
        assertEquals(3, fetches, "the TTL expiry refetches")
    }
}
