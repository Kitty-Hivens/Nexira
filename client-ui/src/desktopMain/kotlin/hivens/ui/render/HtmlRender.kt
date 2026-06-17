package hivens.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import hivens.ui.theme.CelestiaTheme
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
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
    val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
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
    baseColor: Color = CelestiaTheme.colors.textPrimary,
    onLink: (String) -> Unit = ::openInBrowser,
) {
    val body = remember(html) { Jsoup.parse(html).body() }
    val ctx = InlineCtx(linkColor = CelestiaTheme.colors.primary, baseColor = baseColor)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks(body, ctx, onLink)
    }
}

private data class InlineCtx(val linkColor: Color, val baseColor: Color)

private val BLOCK_TAGS = setOf(
    "p", "div", "section", "article", "header", "footer", "main", "aside", "figure",
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
            flush(); out.add(BlockEl(n as Element))
        } else {
            inline.add(n)
        }
    }
    flush()
    return out
}

@Composable
private fun ColumnScope.blocks(parent: Element, ctx: InlineCtx, onLink: (String) -> Unit) {
    for (frag in remember(parent) { group(parent) }) {
        when (frag) {
            is InlineRun -> {
                val ann = buildInline(frag.nodes, ctx, onLink)
                if (ann.text.isNotBlank()) Text(ann, style = TextStyle(color = ctx.baseColor))
            }
            is BlockEl -> block(frag.el, ctx, onLink)
        }
    }
}

@Composable
private fun ColumnScope.block(el: Element, ctx: InlineCtx, onLink: (String) -> Unit) {
    val align = cssTextAlign(el)
    when (el.tagName().lowercase()) {
        "h1", "h2", "h3", "h4", "h5", "h6" -> {
            val base = when (el.tagName().lowercase()) {
                "h1" -> MaterialTheme.typography.headlineSmall
                "h2" -> MaterialTheme.typography.titleLarge
                "h3" -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            Text(
                buildInline(el.childNodes(), ctx, onLink),
                style = base.copy(color = ctx.baseColor, textAlign = align, fontWeight = FontWeight.Bold),
            )
        }
        "p" -> Text(
            buildInline(el.childNodes(), ctx, onLink),
            style = TextStyle(color = ctx.baseColor, textAlign = align),
        )
        "ul", "ol" -> ListBlock(el, ctx, onLink, ordered = el.tagName().equals("ol", true))
        "blockquote" -> Box(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .background(CelestiaTheme.colors.surface).padding(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { blocks(el, ctx, onLink) }
        }
        "pre" -> Text(
            el.wholeText().trimEnd(),
            style = TextStyle(color = ctx.baseColor, fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .background(CelestiaTheme.colors.surface).padding(12.dp),
        )
        "hr" -> HorizontalDivider(color = CelestiaTheme.colors.outline.copy(alpha = 0.4f))
        "img" -> ImageBlock(el)
        "table" -> TableBlock(el, ctx, onLink)
        // div / section / li / summary / details / figure / unknown container -> flow children into this column.
        else -> blocks(el, ctx, onLink)
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        el.select("tr").forEach { tr ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tr.children().filter { it.tagName().equals("td", true) || it.tagName().equals("th", true) }
                    .forEach { cell ->
                        Text(
                            buildInline(cell.childNodes(), ctx, onLink),
                            modifier = Modifier.weight(1f),
                            style = TextStyle(
                                color = ctx.baseColor,
                                fontWeight = if (cell.tagName().equals("th", true)) FontWeight.Bold else FontWeight.Normal,
                            ),
                        )
                    }
            }
        }
    }
}

@Composable
private fun ImageBlock(el: Element) {
    val src = el.attr("src").ifBlank { el.attr("data-src") }
    if (src.isBlank()) return
    var m: Modifier = Modifier
    el.attr("width").toIntOrNull()?.let { m = m.width(it.dp) }
    el.attr("height").toIntOrNull()?.let { m = m.height(it.dp) }
    AsyncImage(
        model = src,
        contentDescription = el.attr("alt").ifBlank { null },
        contentScale = ContentScale.Fit,
        modifier = m,
    )
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
                "code" -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { kids() }
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
