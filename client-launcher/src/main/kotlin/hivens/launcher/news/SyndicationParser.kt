package hivens.launcher.news

import hivens.core.data.NewsItem
import hivens.core.data.NewsPage
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Reads an RSS or Atom document into news entries.
 *
 * Both formats, because a feed URL a user pastes in is as likely to be one as the
 * other and telling them apart is one element name. RSS 2.0 puts its entries in
 * `channel/item` with an RFC 1123 `pubDate`; Atom puts them in `entry` with an ISO
 * `updated` or `published`. Everything else the two disagree about is either
 * absent here or read from whichever element carries it.
 *
 * **Two fields are dropped on purpose, and that is the point of this parser
 * rather than an oversight.**
 *
 * The entry's link never enters the model. A channel like this exists to be read
 * on the rail and not to be a doorway out of the launcher into a page nobody
 * vetted, so the URL is discarded at the boundary: nothing downstream can open it
 * by accident, by a later refactor, or by a surface that ignores the channel's
 * policy.
 *
 * Images are dropped for the same reason and one more. Rendering a remote image
 * means the launcher fetches it, which tells that host the reader's address and
 * puts an unvetted picture on their screen. Without them the whole channel costs
 * exactly one request, to the one host the user named.
 *
 * Nothing here throws. An entry with no readable title is dropped and the rest of
 * the document still parses; a document that yields nothing at all is how the
 * caller learns the URL was not a feed.
 */
internal object SyndicationParser {

    /**
     * Past this an entry is not a headline. Feeds exist that put a whole article
     * in the title element, and the row it lands in shows two lines: the rest is
     * a string carried around a list for nothing.
     */
    private const val MAX_TITLE_CHARS = 300

    /** More than one page of anything is not what this kind of channel is for. */
    private const val MAX_ITEMS = 200

    fun parse(document: String): NewsPage {
        // The XML parser, not the HTML one: it keeps the case of `pubDate` and
        // `media:thumbnail`, and it resolves no DTD and no external entity, which
        // is what a document fetched from an address a user typed requires.
        val doc = runCatching { Jsoup.parse(document, "", Parser.xmlParser()) }.getOrNull()
            ?: return NewsPage()
        val entries = doc.select("item").ifEmpty { doc.select("entry") }
        val items = entries.asSequence()
            .mapNotNull { node ->
                val title = text(node, "title") ?: return@mapNotNull null
                // The id is a hash of whatever the entry offers as its identity,
                // because the model counts in Int and a feed counts in strings. Two
                // entries that collide read as one, which costs a row; the
                // alternative is a key that changes between fetches, which costs
                // the list its identity on every refresh.
                val identity = text(node, "guid") ?: text(node, "id") ?: title
                NewsItem(
                    id = identity.hashCode(),
                    title = title.take(MAX_TITLE_CHARS),
                    dateEpochSeconds = published(node),
                )
            }
            .distinctBy { it.id }
            .take(MAX_ITEMS)
            .toList()
        return NewsPage(items = items, page = 1, totalPages = 1)
    }

    /**
     * The element's text with entities decoded and any markup inside it removed.
     *
     * Feeds put HTML in a title regularly, escaped or in CDATA, and either way it
     * arrives here as text that reads as tags. Blank comes back as null so a
     * present-but-empty element is treated as the absent one it amounts to.
     */
    private fun text(node: Element, name: String): String? {
        val raw = node.selectFirst(name)?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Jsoup.parse(raw).text().trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Epoch seconds from whichever date element the document carries, or 0 when
     * none of them parses -- which the model reads as "no date" rather than as
     * 1970.
     */
    private fun published(node: Element): Long {
        val raw = text(node, "pubDate")
            ?: text(node, "published")
            ?: text(node, "updated")
            // Escaped, not "dc:date": a colon opens a pseudo-selector in jsoup's
            // query syntax, so the unescaped form is a parse error rather than a
            // tag name -- and this is the branch a feed with no other date element
            // takes, which is exactly where it would have thrown.
            ?: text(node, "dc|date")
            ?: return 0L
        return rfc1123(raw) ?: iso(raw) ?: 0L
    }

    private fun rfc1123(raw: String): Long? = runCatching {
        OffsetDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
    }.getOrNull()

    private fun iso(raw: String): Long? = runCatching { Instant.parse(raw).epochSecond }
        .recoverCatching { OffsetDateTime.parse(raw).toEpochSecond() }
        .getOrNull()
}
