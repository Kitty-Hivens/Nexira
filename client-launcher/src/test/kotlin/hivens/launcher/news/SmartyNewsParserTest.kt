package hivens.launcher.news

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parser against the shape of a site index page. The markup here is written
 * to match the upstream one attribute for attribute -- the class names, the
 * order of the date tile's parts, the declining word after the view count -- but
 * carries no upstream content: an entry's text and its author are somebody
 * else's, and a fixture is not the place for either.
 */
class SmartyNewsParserTest {

    private val base = "https://www.example.invalid"

    private fun block(
        id: Int,
        title: String,
        image: String,
        day: String,
        month: String,
        year: String,
        time: String,
        views: String,
        body: String = "Body text.",
    ) = """
        <div id="news$id" class="content-block np">
            <div class="news-block-body">
                <picture>
                    <source srcset="$image.webp" type="image/webp" class="news-block-img-srcset">
                    <img src="$image.jpg" alt=" " class="news-block-img" onClick="location.href='news$id#full';">
                </picture>
                <div class="news-date-block tip" title="<span class='skinavatar small'><span style='background-image:url(avatars/curator_48.png);'></span></span> curator<br />$day $month в $time">
                    <h1>$day</h1>
                    <h2>$year</h2>
                </div>
                <h1>
                    <a href="news$id#full">$title</a>
                </h1>
                <p class="normal">$body</p>
                <div class="news-desc">
                    <h1>0 комментариев</h1>
                    <h1>$views</h1>
                    <a class="button b-green" href="news$id#full">Подробнее</a>
                </div>
            </div>
        </div>
    """.trimIndent()

    private fun page(vararg blocks: String, total: Int? = 45): String {
        val pager = total?.let {
            """<div class="pagination left"><ul>
                 <li class="label">Страница <span class="page-current">1</span> из <span class="page-total">$it</span></li>
               </ul></div>"""
        }.orEmpty()
        return "<html><body><div id=\"content\">${blocks.joinToString("\n")}$pager</div></body></html>"
    }

    private fun dateTile(month: String, year: String) = "$month<br />$year"

    @Test
    fun `reads every field of an entry`() {
        val html = page(
            block(
                id = 482,
                title = "Итоги конкурса",
                image = "images/news/competition/9",
                day = "1",
                month = "августа",
                year = dateTile("Августа", "2026"),
                time = "00:15",
                views = "182 просмотра",
            )
        )

        val parsed = SmartyNewsParser.parse(html, base)

        assertEquals(1, parsed.items.size)
        val item = parsed.items.single()
        assertEquals(482, item.id)
        assertEquals("Итоги конкурса", item.title)
        assertEquals(182, item.views)
        assertEquals("$base/images/news/competition/9.jpg", item.imageUrl)
        assertEquals("$base/images/news/mini/competition/9.jpg", item.thumbnailUrl)
        // 2026-08-01 00:15 in Moscow, which is what the site writes its dates in.
        assertEquals(1_785_532_500L, item.dateEpochSeconds)
    }

    // The archive reaches back to 2013, and Moscow was UTC+4 until October 2014.
    // An hour wrong is a day wrong for an entry published just after midnight.
    @Test
    fun `a date from before the offset changed is read in the offset of its day`() {
        val html = page(
            block(
                id = 12, title = "Старая запись", image = "images/news/news1", day = "1",
                month = "апреля", year = dateTile("Апреля", "2013"), time = "00:15", views = "9 просмотров",
            )
        )
        assertEquals(1_364_760_900L, SmartyNewsParser.parse(html, base).items.single().dateEpochSeconds)
    }

    @Test
    fun `a source outside the news image root has no thumbnail`() {
        val html = """
            <div id="news475" class="content-block np">
                <img src="upload/banner.png" alt=" " class="news-block-img">
                <h1><a href="news475#full">Чужая картинка</a></h1>
            </div>
        """.trimIndent()
        val item = SmartyNewsParser.parse(page(html), base).items.single()
        assertEquals("$base/upload/banner.png", item.imageUrl)
        assertNull(item.thumbnailUrl, "only the news image root keeps thumbnails beside it")
    }

    @Test
    fun `a source that already names the thumbnail is its own thumbnail`() {
        val html = """
            <div id="news474" class="content-block np">
                <img src="images/news/mini/news9.jpg" alt=" " class="news-block-img">
                <h1><a href="news474#full">Уже миниатюра</a></h1>
            </div>
        """.trimIndent()
        val item = SmartyNewsParser.parse(page(html), base).items.single()
        assertEquals("$base/images/news/mini/news9.jpg", item.imageUrl)
        assertEquals(item.imageUrl, item.thumbnailUrl)
    }

    @Test
    fun `the pager says how far the archive goes`() {
        val parsed = SmartyNewsParser.parse(page(block1(), total = 45), base)
        assertEquals(45, parsed.totalPages)
        assertTrue(parsed.hasMore, "page 1 of 45 has more")

        // A page with no pager on it is the whole feed, not an empty one.
        val single = SmartyNewsParser.parse(page(block1(), total = null), base)
        assertEquals(1, single.totalPages)
        assertTrue(!single.hasMore)
    }

    @Test
    fun `the page number is carried through`() {
        val parsed = SmartyNewsParser.parse(page(block1()), base, page = 7)
        assertEquals(7, parsed.page)
        assertTrue(parsed.hasMore)
    }

    @Test
    fun `entries keep their order, newest first`() {
        val html = page(
            block1(),
            block(
                id = 481, title = "Второе", image = "images/news/news1", day = "1",
                month = "июля", year = dateTile("Июля", "2026"), time = "00:15", views = "520 просмотров",
            ),
        )
        assertEquals(listOf(482, 481), SmartyNewsParser.parse(html, base).items.map { it.id })
    }

    @Test
    fun `an entry that cannot be read is dropped and the page survives`() {
        // No title link: the block names itself, but nothing readable is in it.
        val broken = """<div id="news999" class="content-block np"><div class="news-desc"></div></div>"""
        val parsed = SmartyNewsParser.parse(page(broken, block1()), base)
        assertEquals(listOf(482), parsed.items.map { it.id }, "the readable entry still arrives")
    }

    @Test
    fun `markup that carries no entries parses to nothing`() {
        assertTrue(SmartyNewsParser.parse("<html><body>Not this page</body></html>", base).items.isEmpty())
        assertTrue(SmartyNewsParser.parse("", base).items.isEmpty())
    }

    @Test
    fun `the view count is read off the counters, not out of the text`() {
        val html = page(
            block(
                id = 480, title = "Заголовок", image = "images/news/news2", day = "30",
                month = "мая", year = dateTile("Мая", "2026"), time = "14:40", views = "862 просмотра",
                // The word appears in the entry's own text, with a number in front of it.
                body = "Осталось 3 просмотра трансляции, дальше запись.",
            )
        )
        assertEquals(862, SmartyNewsParser.parse(html, base).items.single().views)
    }

    @Test
    fun `a title carrying entities is decoded`() {
        val html = page(
            block(
                id = 479, title = "Сервер &laquo;Industrial&raquo; &amp; друзья", image = "images/news/news3",
                day = "1", month = "июня", year = dateTile("Июня", "2026"), time = "00:15", views = "1 просмотр",
            )
        )
        assertEquals("Сервер «Industrial» & друзья", SmartyNewsParser.parse(html, base).items.single().title)
    }

    @Test
    fun `an entry with no readable date still arrives, dateless`() {
        val html = page(
            block(
                id = 478, title = "Без даты", image = "images/news/news4", day = "1",
                month = "фрухтября", year = dateTile("Фрухтября", "2026"), time = "00:15", views = "5 просмотров",
            )
        )
        val item = SmartyNewsParser.parse(html, base).items.single()
        assertEquals(0L, item.dateEpochSeconds, "an unreadable month reads as no date, not as 1970")
        assertEquals("Без даты", item.title)
    }

    @Test
    fun `an absolute image url is left alone`() {
        val html = page(
            block(
                id = 477, title = "Заголовок", image = "https://cdn.example.invalid/news/1", day = "1",
                month = "мая", year = dateTile("Мая", "2026"), time = "00:15", views = "5 просмотров",
            )
        )
        assertEquals("https://cdn.example.invalid/news/1.jpg", SmartyNewsParser.parse(html, base).items.single().imageUrl)
    }

    @Test
    fun `an entry with no image is still an entry`() {
        val html = """
            <div id="news476" class="content-block np">
                <div class="news-date-block tip" title="curator<br />1 мая в 00:15"><h1>1</h1><h2>Мая<br />2026</h2></div>
                <h1><a href="news476#full">Без картинки</a></h1>
                <div class="news-desc"><h1>7 просмотров</h1></div>
            </div>
        """.trimIndent()
        val item = SmartyNewsParser.parse(page(html), base).items.single()
        assertNull(item.imageUrl)
        assertNull(item.thumbnailUrl)
        assertEquals(7, item.views)
    }

    private fun block1() = block(
        id = 482, title = "Итоги конкурса", image = "images/news/competition/9", day = "1",
        month = "августа", year = dateTile("Августа", "2026"), time = "00:15", views = "182 просмотра",
    )
}
