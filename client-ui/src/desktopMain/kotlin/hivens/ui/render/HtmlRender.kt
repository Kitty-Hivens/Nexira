package hivens.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import hivens.ui.components.isPlayableVideoUrl
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.awt.Desktop
import java.net.URI

/**
 * In-launcher HTML renderer -- the velocipede before the standalone lib extraction
 * (see ~/.claude/plans/reflective-napping-pizza.md). jsoup parses; a recursive
 * tag-map paints a known subset to Compose composables, and Compose owns text
 * layout. Unknown markup degrades to its text content -- never a misleading guess.
 *
 * Deliberately block-flow only: no CSS box model, flex or grid (those land in the
 * extracted Compose-MP engine). Honoured CSS is the inline subset that maps 1:1 to
 * Compose params -- `color` (spans) + `text-align`/`align` (blocks); everything
 * else is ignored, not faked.
 */

/** Convert GitHub-flavoured markdown to an HTML string for [HtmlBody]. */
fun markdownToHtml(markdown: String): String {
    val flavour = GFMFlavourDescriptor()
    // The explicit CancellationToken and the CharSequence-typed input select the
    // non-deprecated MarkdownParser API; the bare-flavour constructor and the
    // String buildMarkdownTreeFromString overload are deprecated. NonCancellable
    // is the constructor default -- passed only to reach the primary constructor.
    val source: CharSequence = markdown
    val tree = MarkdownParser(flavour, cancellationToken = CancellationToken.NonCancellable)
        .buildMarkdownTreeFromString(source)
    return HtmlGenerator(markdown, tree, flavour).generateHtml()
}

/** Open a link in the system browser; best-effort, never throws into the UI. */
fun openInBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

/** Markdown -> HTML -> [HtmlBody], one path (Modrinth bodies are md with embedded HTML). */
@Composable
fun MarkdownHtml(
    markdown: String,
    modifier: Modifier = Modifier,
    onLink: (String) -> Unit = ::openInBrowser,
) {
    val html = remember(markdown) { markdownToHtml(markdown) }
    HtmlBody(html, modifier, onLink = onLink)
}

@Composable
fun HtmlBody(
    html: String,
    modifier: Modifier = Modifier,
    baseColor: Color = NxTheme.colors.textPrimary,
    onLink: (String) -> Unit = ::openInBrowser,
) {
    val body = remember(html) { Jsoup.parse(html).body() }
    val ctx = InlineCtx(
        linkColor = NxTheme.colors.primary,
        baseColor = baseColor,
        codeBg = NxTheme.colors.surface,
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks(body, ctx, onLink)
    }
}

private data class InlineCtx(val linkColor: Color, val baseColor: Color, val codeBg: Color)

private val BLOCK_TAGS = setOf(
    "p", "div", "section", "article", "header", "footer", "main", "aside", "figure", "center",
    "h1", "h2", "h3", "h4", "h5", "h6",
    "ul", "ol", "li", "blockquote", "pre", "hr", "table", "thead", "tbody", "tr", "details", "summary",
)

private sealed interface Frag
private class InlineRun(val nodes: List<Node>) : Frag
private class BlockEl(val el: Element) : Frag

/** Split a parent's children into block elements and runs of inline nodes (pure -- no composition). */
private fun group(parent: Element): List<Frag> {
    val out = ArrayList<Frag>()
    val inline = ArrayList<Node>()
    fun flush() { if (inline.isNotEmpty()) { out.add(InlineRun(inline.toList())); inline.clear() } }
    for (n in parent.childNodes()) {
        val tag = (n as? Element)?.tagName()?.lowercase()
        if (tag != null && (tag in BLOCK_TAGS || tag == "img")) {
            flush(); out.add(BlockEl(n))
        } else {
            inline.add(n)
        }
    }
    flush()
    return out
}

@Composable
private fun ColumnScope.blocks(parent: Element, ctx: InlineCtx, onLink: (String) -> Unit, center: Boolean = false) {
    for (frag in remember(parent) { group(parent) }) {
        when (frag) {
            is InlineRun -> {
                val ann = buildInline(frag.nodes, ctx, onLink)
                if (ann.text.isNotBlank()) Text(
                    ann,
                    style    = TextStyle(color = ctx.baseColor, textAlign = if (center) TextAlign.Center else TextAlign.Unspecified),
                    modifier = if (center) Modifier.fillMaxWidth() else Modifier,
                )
            }
            is BlockEl -> block(frag.el, ctx, onLink, center)
        }
    }
}

@Composable
private fun ColumnScope.block(el: Element, ctx: InlineCtx, onLink: (String) -> Unit, center: Boolean = false) {
    val align = cssTextAlign(el).let { if (it == TextAlign.Unspecified && center) TextAlign.Center else it }
    when (el.tagName().lowercase()) {
        // <center> (and align=center containers): center BOTH text and images.
        // A centered image-only run (a banner wrapped in center>a>img) renders as
        // a real image, not its empty alt.
        "center" -> {
            val imgs = imageRunOf(el)
            if (imgs != null) ImageRunBlock(imgs, onLink, center = true)
            else Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { blocks(el, ctx, onLink, center = true) }
        }
        "h1", "h2", "h3", "h4", "h5", "h6" -> {
            val tag = el.tagName().lowercase()
            val base = when (tag) {
                "h1" -> MaterialTheme.typography.headlineMedium
                "h2" -> MaterialTheme.typography.headlineSmall
                "h3" -> MaterialTheme.typography.titleLarge
                "h4" -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            // Extra air above a heading so sections read as sections, not one wall.
            val top = if (tag == "h1" || tag == "h2") 10.dp else 4.dp
            Text(
                buildInline(el.childNodes(), ctx, onLink),
                style    = base.copy(color = ctx.baseColor, textAlign = align, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = top).then(if (center) Modifier.fillMaxWidth() else Modifier),
            )
        }
        "p", "figure" -> {
            // Image-only paragraph (badges / a banner / a video thumbnail, however
            // wrapped) -> clickable images. A paragraph that (invalidly, but as
            // Modrinth emits) holds block children -> flow them. Otherwise inline text.
            val imgs = imageRunOf(el)
            when {
                imgs != null -> ImageRunBlock(imgs, onLink, center = center)
                el.children().any { it.tagName().lowercase() in BLOCK_TAGS || it.tagName().equals("img", true) } ->
                    blocks(el, ctx, onLink, center)
                else -> Text(
                    buildInline(el.childNodes(), ctx, onLink),
                    style    = TextStyle(color = ctx.baseColor, textAlign = align),
                    modifier = if (center) Modifier.fillMaxWidth() else Modifier,
                )
            }
        }
        "ul", "ol" -> ListBlock(el, ctx, onLink, ordered = el.tagName().equals("ol", true))
        "blockquote" -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(MaterialTheme.shapes.small).background(NxTheme.colors.surface)) {
            // Left accent bar so a quote reads as a quote, not a generic box.
            Box(Modifier.width(3.dp).fillMaxHeight().background(NxTheme.colors.primary.copy(alpha = 0.7f)))
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { blocks(el, ctx, onLink) }
        }
        "pre" -> Text(
            el.wholeText().trimEnd(),
            style = TextStyle(color = ctx.baseColor, fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .background(NxTheme.colors.surface).padding(12.dp),
        )
        "hr" -> HorizontalDivider(color = NxTheme.colors.outline.copy(alpha = 0.4f))
        "img" -> ImageBlock(el)
        "table" -> TableBlock(el, ctx, onLink)
        // div / section / li / summary / details / unknown container -> flow children (propagating center).
        else -> blocks(el, ctx, onLink, center)
    }
}

@Composable
private fun ListBlock(el: Element, ctx: InlineCtx, onLink: (String) -> Unit, ordered: Boolean) {
    val items = el.children().filter { it.tagName().equals("li", true) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { i, li ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (ordered) "${i + 1}." else "•", style = TextStyle(color = ctx.baseColor))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    blocks(li, ctx, onLink)
                }
            }
        }
    }
}

@Composable
private fun TableBlock(el: Element, ctx: InlineCtx, onLink: (String) -> Unit) {
    // Bare Text-in-columns read as loose text, not a table. Give it a surface
    // container plus per-row hairline separators and cell padding so the grid
    // reads. No column borders (block-flow renderer) -- rows carry the structure.
    val rows = el.select("tr")
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(NxTheme.colors.surface)) {
        rows.forEachIndexed { rowIdx, tr ->
            val cells = tr.children().filter { it.tagName().equals("td", true) || it.tagName().equals("th", true) }
            val header = cells.any { it.tagName().equals("th", true) }
            Row(
                modifier              = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                cells.forEach { cell ->
                    Text(
                        buildInline(cell.childNodes(), ctx, onLink),
                        modifier = Modifier.weight(1f),
                        style = TextStyle(
                            color = ctx.baseColor,
                            fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                }
            }
            if (rowIdx < rows.size - 1) HorizontalDivider(color = NxTheme.colors.outline.copy(alpha = 0.2f))
        }
    }
}

@Composable
private fun ImageBlock(el: Element) {
    val src = el.attr("src").ifBlank { el.attr("data-src") }
    if (src.isBlank()) return
    // HTML width/height are CSS px, not dp -- honouring them as dp overflowed the
    // column (a width="660" banner blew past the content). Scale to the column
    // width, capped so a very tall image stays sane.
    AsyncImage(
        model = src,
        contentDescription = el.attr("alt").ifBlank { null },
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
    )
}

/** A still image and its optional wrapping link. */
private data class ImgItem(val src: String, val alt: String, val href: String?)

/**
 * The images of a paragraph whose only significant content is images (bare or
 * link-wrapped) -- the badge / video-thumbnail rows markdown emits as
 * `[![alt](img)](href)`. Returns null when the paragraph carries real text too,
 * so prose paragraphs keep their normal inline rendering.
 */
private fun imageRunOf(el: Element): List<ImgItem>? {
    val items = ArrayList<ImgItem>()
    // Walk the subtree; bail (null) the moment real text shows up, so a prose
    // paragraph keeps normal rendering. Descends through the inline / alignment
    // wrappers Modrinth stacks around banners -- `center > a > img`, `p > span >
    // img`, etc. -- which the old flat scan treated as "not an image run" and
    // dropped to empty alt text.
    fun walk(node: Node, href: String?): Boolean {
        when (node) {
            is TextNode -> if (node.text().isNotBlank()) return false
            is Element -> when (node.tagName().lowercase()) {
                "img" -> items.add(ImgItem(imgSrc(node), node.attr("alt"), href))
                "br" -> {}
                "a" -> {
                    val h = node.attr("href").ifBlank { null } ?: href
                    for (c in node.childNodes()) if (!walk(c, h)) return false
                }
                "center", "span", "font", "p", "div", "picture", "b", "strong", "em", "i" ->
                    for (c in node.childNodes()) if (!walk(c, href)) return false
                else -> return false
            }
        }
        return true
    }
    for (c in el.childNodes()) if (!walk(c, null)) return null
    return items.takeIf { it.isNotEmpty() }
}

private fun imgSrc(img: Element): String = img.attr("src").ifBlank { img.attr("data-src") }

/**
 * A row of images (badges, video thumbnails). Each opens its link via [onLink];
 * a video link gets a play badge and is routed to the in-app player upstream.
 */
@Composable
private fun ImageRunBlock(items: List<ImgItem>, onLink: (String) -> Unit, center: Boolean = false) {
    FlowRow(
        modifier              = if (center) Modifier.fillMaxWidth() else Modifier,
        horizontalArrangement = if (center) Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                                else Arrangement.spacedBy(8.dp),
        verticalArrangement   = Arrangement.spacedBy(8.dp),
    ) {
        for (item in items) {
            val href = item.href
            Box(
                modifier         = if (href != null) Modifier.clickable { onLink(href) } else Modifier,
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model              = item.src.ifBlank { null },
                    contentDescription = item.alt.ifBlank { null },
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier.heightIn(max = 220.dp),
                )
                if (href != null && isPlayableVideoUrl(href)) {
                    Box(
                        modifier         = Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Symbol(NxIcon.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

private fun buildInline(nodes: List<Node>, ctx: InlineCtx, onLink: (String) -> Unit): AnnotatedString =
    buildAnnotatedString { nodes.forEach { appendInline(it, ctx, onLink) } }

private fun AnnotatedString.Builder.appendInline(node: Node, ctx: InlineCtx, onLink: (String) -> Unit) {
    when (node) {
        is TextNode -> append(node.text())
        is Element -> {
            fun kids() = node.childNodes().forEach { appendInline(it, ctx, onLink) }
            when (node.tagName().lowercase()) {
                "br" -> append("\n")
                "b", "strong" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { kids() }
                "i", "em" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { kids() }
                "u", "ins" -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { kids() }
                "s", "del", "strike" -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { kids() }
                "code" -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = ctx.codeBg)) { kids() }
                "a" -> {
                    val href = node.attr("href")
                    if (href.isBlank()) kids() else withLink(
                        LinkAnnotation.Clickable(
                            tag = href,
                            styles = TextLinkStyles(SpanStyle(color = ctx.linkColor, textDecoration = TextDecoration.Underline)),
                        ) { onLink(href) },
                    ) { kids() }
                }
                "span", "font" -> {
                    val c = cssColor(node)
                    if (c == null) kids() else withStyle(SpanStyle(color = c)) { kids() }
                }
                // Inline image -> its alt text (v1; block-level images render as AsyncImage).
                "img" -> node.attr("alt").takeIf { it.isNotBlank() }?.let { append(it) }
                // Unknown inline element -> its content, never the raw tag.
                else -> kids()
            }
        }
    }
}

/** `text-align` / legacy `align` -> Compose [TextAlign]; [TextAlign.Unspecified] when unset. */
private fun cssTextAlign(el: Element): TextAlign {
    val v = el.attr("align").lowercase().ifBlank {
        Regex("(?:^|;)\\s*text-align\\s*:\\s*([^;]+)")
            .find(el.attr("style"))?.groupValues?.getOrNull(1)?.trim()?.lowercase().orEmpty()
    }
    return when (v) {
        "center" -> TextAlign.Center
        "right" -> TextAlign.End
        "justify" -> TextAlign.Justify
        "left" -> TextAlign.Start
        else -> TextAlign.Unspecified
    }
}

/** Inline `style="color: ..."` or legacy `color=` -> Compose [Color]; hex (#rgb/#rrggbb) + a few names. */
private fun cssColor(el: Element): Color? {
    val raw = Regex("(?:^|;)\\s*color\\s*:\\s*([^;]+)")
        .find(el.attr("style"))?.groupValues?.getOrNull(1)?.trim()
        ?: el.attr("color").ifBlank { null }
        ?: return null
    return parseColor(raw)
}

private fun parseColor(raw: String): Color? {
    val v = raw.trim()
    if (v.startsWith("#")) {
        val hex = v.drop(1)
        val rrggbb = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("")
            6 -> hex
            else -> return null
        }
        val n = rrggbb.toLongOrNull(16) ?: return null
        return Color(0xFF000000L or n)
    }
    return NAMED_COLORS[v.lowercase()]
}

private val NAMED_COLORS = mapOf(
    "black" to Color.Black, "white" to Color.White, "red" to Color.Red, "green" to Color.Green,
    "blue" to Color.Blue, "gray" to Color.Gray, "grey" to Color.Gray, "yellow" to Color.Yellow,
    "cyan" to Color.Cyan, "magenta" to Color.Magenta,
)
