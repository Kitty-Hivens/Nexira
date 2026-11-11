package hivens.launcher.news

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What comes out of a feed document, and just as importantly what does not.
 *
 * The channel this feeds is deliberately narrow -- no outbound link, no remote
 * image, one request to one host -- and that narrowness lives in the parser rather
 * than in the surface. So the assertions here are half about reading RSS and Atom
 * correctly and half about the fields that must never reach the model, because a
 * later change that "helpfully" carries the link through would otherwise pass
 * every other test in the tree.
 */
class SyndicationParserTest {

    private val rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
          <channel>
            <title>Some feed</title>
            <link>https://example.invalid</link>
            <item>
              <title>First headline</title>
              <link>https://example.invalid/1</link>
              <guid isPermaLink="false">tag:example,2026:1</guid>
              <pubDate>Thu, 20 Aug 2026 12:00:00 GMT</pubDate>
              <enclosure url="https://example.invalid/1.jpg" type="image/jpeg" length="1"/>
            </item>
            <item>
              <title><![CDATA[Second <b>headline</b>]]></title>
              <link>https://example.invalid/2</link>
              <guid isPermaLink="false">tag:example,2026:2</guid>
              <pubDate>Fri, 21 Aug 2026 09:30:00 +0300</pubDate>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    private val atom = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Some feed</title>
          <entry>
            <title>Atom headline</title>
            <link rel="alternate" href="https://example.invalid/a"/>
            <id>urn:uuid:1</id>
            <updated>2026-08-20T12:00:00Z</updated>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `rss items become entries, newest order preserved`() {
        val page = SyndicationParser.parse(rss)
        assertEquals(listOf("First headline", "Second headline"), page.items.map { it.title })
        assertEquals(1, page.page)
        assertEquals(1, page.totalPages)
        assertTrue(!page.hasMore, "a syndication document is the whole channel, not a page of it")
    }

    @Test
    fun `an rfc 1123 pubDate is read, in gmt and at an offset`() {
        val page = SyndicationParser.parse(rss)
        // 2026-08-20T12:00:00Z
        assertEquals(1787227200L, page.items[0].dateEpochSeconds)
        // 2026-08-21T09:30:00+03:00 == 06:30:00Z
        assertEquals(1787293800L, page.items[1].dateEpochSeconds)
    }

    @Test
    fun `atom entries are read with their iso date`() {
        val page = SyndicationParser.parse(atom)
        assertEquals(listOf("Atom headline"), page.items.map { it.title })
        assertEquals(1787227200L, page.items.single().dateEpochSeconds)
    }

    @Test
    fun `markup inside a title is text, not tags`() {
        val page = SyndicationParser.parse(rss)
        assertEquals("Second headline", page.items[1].title)
    }

    /**
     * The two fields the channel exists without. Nothing on [hivens.core.data.NewsItem]
     * can carry the link at all, which is the point; the images it CAN carry stay
     * null so no remote fetch is ever started for this channel.
     */
    @Test
    fun `no image url reaches the model even when the feed publishes one`() {
        val page = SyndicationParser.parse(rss)
        assertTrue(page.items.all { it.imageUrl == null && it.thumbnailUrl == null }, "${page.items}")
    }

    @Test
    fun `ids are stable across parses of the same document`() {
        val first = SyndicationParser.parse(rss).items.map { it.id }
        val second = SyndicationParser.parse(rss).items.map { it.id }
        assertEquals(first, second, "a key that moves between fetches costs the list its identity")
        assertEquals(first.distinct(), first, "two entries must not collapse into one row")
    }

    @Test
    fun `an entry with no title is dropped and the rest still parse`() {
        val page = SyndicationParser.parse(
            """
            <rss version="2.0"><channel>
              <item><guid>a</guid></item>
              <item><title>Kept</title><guid>b</guid></item>
              <item><title>   </title><guid>c</guid></item>
            </channel></rss>
            """.trimIndent(),
        )
        assertEquals(listOf("Kept"), page.items.map { it.title })
    }

    @Test
    fun `a document that is not a feed parses to nothing rather than throwing`() {
        assertEquals(0, SyndicationParser.parse("<html><body>hello</body></html>").items.size)
        assertEquals(0, SyndicationParser.parse("").items.size)
        assertEquals(0, SyndicationParser.parse("{\"not\":\"xml\"}").items.size)
        assertEquals(0, SyndicationParser.parse("<rss><channel>").items.size)
    }

    /**
     * The document comes from an address the reader typed, so it is hostile input.
     * An external entity must not be resolved: if it were, this title would come
     * back holding the contents of a local file.
     */
    @Test
    fun `an external entity is not resolved`() {
        val page = SyndicationParser.parse(
            """
            <?xml version="1.0"?>
            <!DOCTYPE rss [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <rss version="2.0"><channel>
              <item><title>&xxe;</title><guid>x</guid></item>
              <item><title>Ordinary</title><guid>y</guid></item>
            </channel></rss>
            """.trimIndent(),
        )
        assertTrue(
            page.items.none { it.title.contains("root:") || it.title.contains("/bin/") },
            "an external entity was resolved into an entry: ${page.items.map { it.title }}",
        )
    }

    /**
     * A billion-laughs expansion must not be attempted either. The assertion is
     * that this returns at all -- an expanding parser would not.
     */
    @Test
    fun `a recursive entity does not expand`() {
        val page = SyndicationParser.parse(
            """
            <?xml version="1.0"?>
            <!DOCTYPE rss [
              <!ENTITY a "aaaaaaaaaa">
              <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
              <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
              <!ENTITY d "&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;">
            ]>
            <rss version="2.0"><channel>
              <item><title>&d;</title><guid>z</guid></item>
            </channel></rss>
            """.trimIndent(),
        )
        assertTrue(page.items.all { it.title.length <= 300 }, "an entity expanded into the model")
    }

    @Test
    fun `an absurd title is cut to a headline`() {
        val page = SyndicationParser.parse(
            """
            <rss version="2.0"><channel>
              <item><title>${"x".repeat(5000)}</title><guid>q</guid></item>
            </channel></rss>
            """.trimIndent(),
        )
        assertEquals(300, page.items.single().title.length)
    }

    @Test
    fun `an unparseable date reads as no date rather than as 1970`() {
        val page = SyndicationParser.parse(
            """
            <rss version="2.0"><channel>
              <item><title>Undated</title><guid>u</guid><pubDate>last tuesday</pubDate></item>
            </channel></rss>
            """.trimIndent(),
        )
        assertEquals(0L, page.items.single().dateEpochSeconds)
    }
}
