package hivens.ui.identity

import hivens.core.api.HttpClientProvider
import hivens.core.time.Clock
import hivens.core.time.SystemClock
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * The player's clan standing -- the capability behind the clan-cape upload
 * (the server gates it to the clan leader). [Unknown] is a first-class
 * answer: page unreachable, markup drifted, or the profile block missing all
 * mean "cannot prove ineligibility", and the cape UI fails OPEN on it -- a
 * false-show costs one failed upload with the existing hint, a false-hide
 * silently locks a legitimate leader out.
 */
enum class ClanRole { Leader, NotLeader, NoClan, Unknown }

/**
 * Resolves clan standing from the PUBLIC player page
 * (`smartycraft.ru/player_<nick>`), which states it outright: a leader's
 * profile block reads "Лидер клана <link>", a member's "Участник клана
 * <link>", and a clan-less player has no clan link at all. One
 * unauthenticated GET, independent of how stale the persisted session is
 * (the session's clan tag only serves the no-network fast path in the
 * wardrobe). Results cache in memory per nick for [CACHE_TTL_MS].
 */
class ClanRoleProvider internal constructor(
    private val fetchPage: suspend (String) -> String?,
    private val clock: Clock,
) {
    constructor(clientProvider: HttpClientProvider, clock: Clock = SystemClock) : this(
        fetchPage = { url -> fetchViaKtor(clientProvider, url) },
        clock = clock,
    )

    private data class Cached(val role: ClanRole, val at: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun eligibility(nick: String): ClanRole {
        cache[nick]?.takeIf { clock.nowMillis() - it.at <= CACHE_TTL_MS }?.let { return it.role }
        val html = fetchPage("$BASE_URL${URLEncoder.encode(nick, StandardCharsets.UTF_8)}")
        val role = html?.let { parseEligibility(it, nick) } ?: ClanRole.Unknown
        cache[nick] = Cached(role, clock.nowMillis())
        return role
    }

    private companion object {
        private const val BASE_URL = "https://www.smartycraft.ru/player_"
        private const val CACHE_TTL_MS = 30 * 60 * 1000L

        private val log = LoggerFactory.getLogger(ClanRoleProvider::class.java)

        // The site renders the profile block in Russian; this is the wire
        // marker the page carries, not UI text of ours.
        private const val LEADER_MARKER = "Лидер" // i18n-allow

        private suspend fun fetchViaKtor(clientProvider: HttpClientProvider, url: String): String? =
            withContext(Dispatchers.IO) {
                try {
                    val response = clientProvider.current.get(url) {
                        header(HttpHeaders.UserAgent, "Mozilla/5.0")
                    }
                    if (response.status.isSuccess()) response.bodyAsText() else null
                } catch (e: Exception) {
                    log.debug("player page fetch failed for {}: {}", url, e.message)
                    null
                }
            }
    }

    /**
     * Reads the profile block. The page must first prove it IS this player's
     * profile (the `stats_player_<nick>` button every profile carries) --
     * otherwise a 404 / maintenance / search-redirect page with no clan link
     * would masquerade as a positive "no clan". Then: no `clan_<TAG>` link
     * anywhere means no clan; a clan heading starting with the leader marker
     * means leader; any other clan heading (member, deputy) is positively
     * not the leader.
     */
    internal fun parseEligibility(html: String, nick: String): ClanRole = try {
        val doc = Jsoup.parse(html)
        val isProfile = doc.select("a[href]").any {
            it.attr("href").equals("stats_player_$nick", ignoreCase = true)
        }
        if (!isProfile) {
            ClanRole.Unknown
        } else {
            val clanHeading = doc.select("h2:has(a[href^=clan_])").firstOrNull()
            when {
                clanHeading == null -> ClanRole.NoClan
                clanHeading.text().trim().startsWith(LEADER_MARKER, ignoreCase = true) -> ClanRole.Leader
                else -> ClanRole.NotLeader
            }
        }
    } catch (e: Exception) {
        log.debug("player profile parse failed: {}", e.message)
        ClanRole.Unknown
    }
}
