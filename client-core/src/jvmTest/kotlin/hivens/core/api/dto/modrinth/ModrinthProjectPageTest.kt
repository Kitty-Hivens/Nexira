package hivens.core.api.dto.modrinth

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fields the project page needs, decoded from the shapes the live API
 * actually sends. The payloads below are excerpts captured from Modrinth on
 * 2026-09-18 rather than written by hand, because the interesting parts of this
 * decode are the ones nobody would invent: a disclosure that carries a consent
 * mode and no note, one that carries a list and no consent, and a key
 * (`updated_at`) that the client has no field for and must ignore rather than
 * fail on.
 */
class ModrinthProjectPageTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `the environment pair, the counts and the links decode`() {
        val payload = """
            {
              "id": "AANobbMI", "slug": "sodium", "title": "Sodium",
              "client_side": "required", "server_side": "unsupported",
              "loaders": ["fabric", "neoforge", "quilt"],
              "game_versions": ["1.21.1", "1.21.4"],
              "downloads": 226905589, "followers": 40626,
              "published": "2021-01-03T00:53:34.185936Z",
              "updated": "2026-09-15T16:04:10.867760Z",
              "source_url": "https://github.com/CaffeineMC/sodium",
              "donation_urls": [{"id":"ko-fi","platform":"Ko-fi","url":"https://caffeinemc.net/donate"}],
              "files": "a key the client has never heard of"
            }
        """.trimIndent()

        val p = json.decodeFromString<ModrinthProject>(payload)

        // Read as a pair. Neither half means anything on its own.
        assertEquals("required", p.clientSide)
        assertEquals("unsupported", p.serverSide)
        assertEquals(listOf("fabric", "neoforge", "quilt"), p.loaders)
        assertEquals(226_905_589L, p.downloads)
        assertEquals("https://github.com/CaffeineMC/sodium", p.sourceUrl)
        assertEquals("Ko-fi", p.donationUrls.single().platform)
        // Absent optional links stay null rather than becoming an empty string,
        // because the page shows a link row only when there is a link.
        assertEquals(null, p.issuesUrl)
    }

    @Test
    fun `a project that declares three things keeps all three, and their shapes differ`() {
        val payload = """
            {"disclosures": [
              {"type": "advertisements", "note": "A list of featured servers is visible in the multiplayer menu", "updated_at": "2026-08-13T16:36:47.465485Z"},
              {"type": "paid_features", "features": ["Optional cosmetics are available for purchase"], "updated_at": "2026-08-13T16:34:31.643594Z"},
              {"type": "telemetry", "consent": "opt_out", "data_collected": ["https://essential.gg/privacy-policy"], "updated_at": "2026-08-13T16:29:52.980331Z"}
            ]}
        """.trimIndent()

        val d = json.decodeFromString<ModrinthDisclosures>(payload).disclosures

        assertEquals(3, d.size)
        val ads = d.single { it.type == ModrinthDisclosure.ADVERTISEMENTS }
        assertEquals("A list of featured servers is visible in the multiplayer menu", ads.note)
        assertTrue(ads.features.isEmpty())

        val paid = d.single { it.type == ModrinthDisclosure.PAID_FEATURES }
        assertEquals(listOf("Optional cosmetics are available for purchase"), paid.features)
        assertEquals(null, paid.note)

        // The consent mode is the whole of what a reader wants from this one, so
        // it must never collapse into the bare word "telemetry".
        val telemetry = d.single { it.type == ModrinthDisclosure.TELEMETRY }
        assertEquals("opt_out", telemetry.consent)
        assertEquals(listOf("https://essential.gg/privacy-policy"), telemetry.dataCollected)
    }

    @Test
    fun `a declaration of a kind we have never seen still arrives`() {
        // The vocabulary is the server's and it grows. An unknown type has to
        // reach the page as itself; collapsing it into an enum's else branch
        // would silently hide the newest thing an author can disclose.
        val d = json.decodeFromString<ModrinthDisclosures>(
            """{"disclosures":[{"type":"something_invented_next_year","note":"whatever it is"}]}""",
        ).disclosures

        assertEquals("something_invented_next_year", d.single().type)
        assertEquals("whatever it is", d.single().note)
    }

    @Test
    fun `declaring nothing is an answer and decodes as an empty list`() {
        val d = json.decodeFromString<ModrinthDisclosures>("""{"disclosures":[]}""")
        assertTrue(d.disclosures.isEmpty())
    }
}
