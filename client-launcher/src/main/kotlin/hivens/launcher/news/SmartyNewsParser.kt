package hivens.launcher.news

import hivens.core.data.NewsItem
import hivens.core.data.NewsPage
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Reads a SmartyCraft index page into news entries.
 *
 * The site paginates its own archive at `index_page<n>`, ten entries a page,
 * newest first -- which is where the feed past the dashboard's three lives. Each
 * entry is a `content-block` div named after its news id, and every field this
 * takes is anchored on a class or an id rather than on position, so a change to
 * the surrounding layout does not move it.
 *
 * Nothing here throws. A block whose id or title cannot be read is dropped and
 * the rest of the page still parses: a feed missing one entry is worth more than
 * an exception in place of the page. A page that yields nothing at all is how
 * the caller learns the markup moved out from under this.
 */
internal object SmartyNewsParser {

    /**
     * Parse [html] into a page of entries. [baseUrl] is the site origin, used to
     * absolutise the image paths the markup writes relative.
     */
    fun parse(html: String, baseUrl: String, page: Int = 1): NewsPage {
        val items = blocks(html).mapNotNull { block -> item(block, baseUrl) }
        return NewsPage(items = items, page = page, totalPages = totalPages(html))
    }

    /**
     * How many pages the archive has, from the pager's own count. One when the
     * pager is absent -- a page with no pager is the whole feed.
     */
    private fun totalPages(html: String): Int =
        between(html, "<span class=\"page-total\">", "</span>")
            ?.trim()
            ?.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: 1

    /** Each entry's markup, split on the div that carries its news id. */
    private fun blocks(html: String): List<String> {
        val marks = mutableListOf<Int>()
        var at = html.indexOf(BLOCK_MARK)
        while (at >= 0) {
            marks += at
            at = html.indexOf(BLOCK_MARK, at + BLOCK_MARK.length)
        }
        return marks.mapIndexed { i, start ->
            html.substring(start, marks.getOrNull(i + 1) ?: html.length)
        }
    }

    private fun item(block: String, baseUrl: String): NewsItem? {
        val id = between(block, BLOCK_MARK, "\"")?.toIntOrNull() ?: return null
        val title = title(block, id) ?: return null
        val image = imageSrc(block)
        return NewsItem(
            id = id,
            title = title,
            views = views(block),
            dateEpochSeconds = publishedAt(block),
            imageUrl = image?.let { absolute(it, baseUrl) },
            thumbnailUrl = image?.let(::thumbnailOf)?.let { absolute(it, baseUrl) },
        )
    }

    /**
     * The headline, from the entry's own link to itself. Anchored on that link
     * rather than on "the first h1 in the block": the date block above it is
     * written with h1s too.
     */
    private fun title(block: String, id: Int): String? =
        between(block, "<a href=\"news$id#full\">", "</a>")
            ?.let(::text)
            ?.takeIf { it.isNotBlank() }

    /**
     * The entry's image as the page writes it. The tag carries several attributes
     * in no guaranteed order, so the class picks the tag and the src is read out
     * of that tag rather than out of the block.
     */
    private fun imageSrc(block: String): String? {
        val tag = tags(block, "<img ").firstOrNull { it.contains(IMAGE_CLASS) } ?: return null
        return between(tag, "src=\"", "\"")?.takeIf { it.isNotBlank() }
    }

    /**
     * The thumbnail the site keeps beside a news image: the same path with one
     * directory inserted. Only paths under the news image root have one, and a
     * path that already names it is its own thumbnail.
     */
    private fun thumbnailOf(src: String): String? = when {
        THUMB_ROOT in src -> src
        IMAGE_ROOT in src -> src.replaceFirst(IMAGE_ROOT, THUMB_ROOT)
        else -> null
    }

    private fun absolute(src: String, baseUrl: String): String = when {
        src.startsWith("http://") || src.startsWith("https://") -> src
        src.startsWith("/") -> "${baseUrl.trimEnd('/')}$src"
        else -> "${baseUrl.trimEnd('/')}/$src"
    }

    /**
     * Publication time as an epoch second, 0 when the block does not carry a
     * readable one.
     *
     * The date tile holds the day, the month and the year; the tooltip beside it
     * holds the time of day. Read together they are a timestamp -- and the site
     * writes them in Moscow time, which is what the offset below converts from
     * (verified against the epoch-named image files the same entries carry).
     */
    private fun publishedAt(block: String): Long {
        val tile = block.indexOf(DATE_CLASS).takeIf { it >= 0 } ?: return 0L
        val rest = block.substring(tile)
        val day = between(rest, "<h1>", "</h1>")?.trim()?.toIntOrNull() ?: return 0L
        val stamp = between(rest, "<h2>", "</h2>") ?: return 0L
        val parts = stamp.split("<br />", "<br/>", "<br>")
        val month = MONTHS[parts.firstOrNull()?.trim()?.lowercase()] ?: return 0L
        val year = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return 0L
        val (hour, minute) = timeOfDay(rest)
        return runCatching {
            LocalDateTime.of(year, month, day, hour, minute).atZone(MOSCOW).toEpochSecond()
        }.getOrDefault(0L)
    }

    /** `... в 00:15` out of the date tile's tooltip; midnight when it is absent. */
    private fun timeOfDay(rest: String): Pair<Int, Int> {
        val tip = between(rest, "title=\"", "\"") ?: return 0 to 0
        val at = tip.indexOf(TIME_MARK).takeIf { it >= 0 } ?: return 0 to 0
        val clock = tip.substring(at + TIME_MARK.length).trim().take(5).split(':')
        val hour = clock.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..23 } ?: return 0 to 0
        val minute = clock.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
        return hour to minute
    }

    /**
     * The view count, read out of the counters strip rather than out of the
     * block: the entry's own text may well contain the word, and the number in
     * front of that one would not be a view count. The word itself declines with
     * the number, so only the number is read.
     */
    private fun views(block: String): Int {
        val strip = block.indexOf(DESC_CLASS).takeIf { it >= 0 } ?: return 0
        val rest = block.substring(strip)
        val at = rest.indexOf(VIEWS_MARK).takeIf { it >= 0 } ?: return 0
        val open = rest.lastIndexOf("<h1>", at).takeIf { it >= 0 } ?: return 0
        return rest.substring(open + 4, at).trim().toIntOrNull() ?: 0
    }

    /** Every `<tag ...>` opening with [open], as whole tags. */
    private fun tags(block: String, open: String): List<String> {
        val out = mutableListOf<String>()
        var at = block.indexOf(open)
        while (at >= 0) {
            val end = block.indexOf('>', at)
            if (end < 0) break
            out += block.substring(at, end + 1)
            at = block.indexOf(open, end)
        }
        return out
    }

    private fun between(haystack: String, start: String, end: String): String? {
        val from = haystack.indexOf(start).takeIf { it >= 0 } ?: return null
        val open = from + start.length
        val to = haystack.indexOf(end, open).takeIf { it >= 0 } ?: return null
        return haystack.substring(open, to)
    }

    /** Markup to reading text: tags out, entities decoded, whitespace collapsed. */
    private fun text(raw: String): String {
        val out = StringBuilder(raw.length)
        var inTag = false
        for (ch in raw) {
            when {
                ch == '<' -> inTag = true
                ch == '>' -> inTag = false
                !inTag -> out.append(ch)
            }
        }
        return entities(out.toString()).replace(WHITESPACE, " ").trim()
    }

    /** The named references this markup actually uses, plus numeric ones. */
    private fun entities(raw: String): String {
        if ('&' !in raw) return raw
        val out = StringBuilder(raw.length)
        var at = 0
        while (at < raw.length) {
            val amp = raw.indexOf('&', at)
            if (amp < 0) {
                out.append(raw, at, raw.length)
                break
            }
            out.append(raw, at, amp)
            val semi = raw.indexOf(';', amp).takeIf { it in (amp + 1)..(amp + 10) }
            if (semi == null) {
                out.append('&')
                at = amp + 1
                continue
            }
            val name = raw.substring(amp + 1, semi)
            val decoded = when {
                name.startsWith("#x") || name.startsWith("#X") ->
                    name.drop(2).toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) }
                name.startsWith("#") ->
                    name.drop(1).toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) }
                else -> NAMED[name]
            }
            if (decoded == null) {
                out.append('&')
                at = amp + 1
            } else {
                out.append(decoded)
                at = semi + 1
            }
        }
        return out.toString()
    }

    private const val BLOCK_MARK = "<div id=\"news"
    private const val IMAGE_CLASS = "news-block-img"
    private const val IMAGE_ROOT = "images/news/"
    private const val THUMB_ROOT = "images/news/mini/"
    private const val DATE_CLASS = "news-date-block"
    private const val DESC_CLASS = "news-desc"
    // Input, not output: these are the literals the upstream page is written in,
    // matched against its markup. They belong to the site's wire format the same
    // way an element class does, so they stay in Russian whatever language the
    // launcher is running in -- translating them would stop the parse.
    private const val VIEWS_MARK = " просмотр" // i18n-allow
    private const val TIME_MARK = " в " // i18n-allow

    /**
     * The zone the site writes its dates in, as a zone rather than a fixed
     * offset: Moscow was UTC+4 between 2011 and 2014, and the archive reaches
     * back past that. An hour wrong is a day wrong for anything published near
     * midnight, which is most of it.
     */
    private val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")
    private val WHITESPACE = Regex("\\s+")

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"",
        "apos" to "'", "nbsp" to " ", "laquo" to "«", "raquo" to "»",
        "mdash" to "—", "ndash" to "–", "hellip" to "…",
    )

    /**
     * Month names as the date tile writes them, in the genitive the site uses.
     * Parser input like [VIEWS_MARK]; the rendered date comes from the launcher's
     * own formatting, not from these.
     */
    private val MONTHS = mapOf(
        "января" to 1, "февраля" to 2, "марта" to 3, "апреля" to 4, // i18n-allow
        "мая" to 5, "июня" to 6, "июля" to 7, "августа" to 8, // i18n-allow
        "сентября" to 9, "октября" to 10, "ноября" to 11, "декабря" to 12, // i18n-allow
    )
}
