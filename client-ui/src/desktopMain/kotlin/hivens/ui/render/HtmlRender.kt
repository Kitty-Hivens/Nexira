package hivens.ui.render

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import java.util.Locale
import org.slf4j.LoggerFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil3.size.Size as CoilSize
import hivens.ui.components.isPlayableVideoUrl
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.bevelHairline
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

/**
 * True when [url] is safe to hand to the platform opener.
 *
 * Only http and https. These links arrive in pack descriptions and mod
 * metadata -- text a third party wrote -- and `Desktop.browse` passes whatever
 * it gets to the system handler, which acts on `file:`, `smb:` and every
 * scheme some installed application has registered for. A link that reads like
 * a homepage should not be able to open a local file or reach for a network
 * share, and the user sees the label, not the target.
 */
fun isBrowsableUrl(url: String): Boolean {
    val scheme = runCatching { URI(url) }.getOrNull()?.scheme?.lowercase(Locale.ROOT) ?: return false
    return scheme == "http" || scheme == "https"
}

/**
 * True when [url] is safe for the page to fetch by itself.
 *
 * The same http/https restriction [isBrowsableUrl] applies to a link, plus the
 * `data:` form, which carries its own bytes and reaches nothing. An image source
 * differs from a link in that nobody clicks it -- the fetch happens because the
 * description said so -- which is the argument for the gate, not against it.
 */
fun isFetchableUrl(url: String): Boolean =
    isBrowsableUrl(url) || url.startsWith("data:", ignoreCase = true)

/** Open a link in the system browser; best-effort, never throws into the UI. */
fun openInBrowser(url: String) {
    if (!isBrowsableUrl(url)) {
        LoggerFactory.getLogger("HtmlRender").warn("Refusing to open a link that is not http(s): {}", url.take(120))
        return
    }
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

/**
 * Markdown -> HTML -> [HtmlBody], one path (Modrinth bodies are md with embedded
 * HTML).
 *
 * Both parses happen off the composition. A `remember` calculation runs inside
 * composition, which here is the UI thread, and a hundred-kilobyte description
 * pays a markdown tree build, an HTML generation and a second full HTML parse
 * there before the first frame of the page can be drawn.
 */
@Composable
fun MarkdownHtml(
    markdown: String,
    modifier: Modifier = Modifier,
    onLink: (String) -> Unit = ::openInBrowser,
) {
    val html by produceState<String?>(null, markdown) {
        value = withContext(Dispatchers.Default) { markdownToHtml(markdown) }
    }
    html?.let { HtmlBody(it, modifier, onLink = onLink) }
}

@Composable
fun HtmlBody(
    html: String,
    modifier: Modifier = Modifier,
    baseColor: Color = NxTheme.colors.textPrimary,
    onLink: (String) -> Unit = ::openInBrowser,
) {
    val parsed by produceState<Element?>(null, html) {
        value = withContext(Dispatchers.Default) { Jsoup.parse(html).body() }
    }
    val body = parsed ?: return
    val ctx = InlineCtx(
        linkColor = linkColor(baseColor),
        baseColor = baseColor,
        codeBg = NxTheme.colors.surface,
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
        blocks(body, ctx, onLink)
    }
}

private data class InlineCtx(val linkColor: Color, val baseColor: Color, val codeBg: Color)

/**
 * What a link in a description is drawn in.
 *
 * Deliberately not the theme's accent. A description is not part of the app's
 * chrome and its links are not app controls: on the default palette the accent
 * is violet, which is the colour a browser has meant "you have already been
 * there" for thirty years, so every link in every pack read as visited. Blue is
 * what a link is, and it stays blue whatever the palette is set to -- the one
 * thing here that has to be recognisable before it is read.
 *
 * The two shades are for the ground it sits on, not for the theme.
 */
private fun linkColor(onBase: Color): Color =
    if (onBase.luminance() > 0.5f) LINK_ON_DARK else LINK_ON_LIGHT

private val LINK_ON_DARK = Color(0xFF63A9FF)
private val LINK_ON_LIGHT = Color(0xFF1A62CC)

/**
 * Air between two blocks of prose. A description is paragraphs, lists and code
 * with headings over them, and at the old eight it read as one column of text
 * with the headings floating in it rather than as sections.
 */
private val BLOCK_GAP = 14.dp

/** Prose leading. Compose's default is tight for a body of running text. */
private val PROSE_LINE_HEIGHT = 1.5.em

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
                    style    = TextStyle(
                        color = ctx.baseColor,
                        lineHeight = PROSE_LINE_HEIGHT,
                        textAlign = if (center) TextAlign.Center else TextAlign.Unspecified,
                    ),
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
            val ruled = tag == "h1" || tag == "h2"
            Column(Modifier.padding(top = top)) {
                Text(
                    buildInline(el.childNodes(), ctx, onLink),
                    style    = base.copy(color = ctx.baseColor, textAlign = align, fontWeight = FontWeight.Bold),
                    modifier = if (center) Modifier.fillMaxWidth() else Modifier,
                )
                // A rule under the top two levels. Bold alone does not separate a
                // section from the paragraph above it once a description runs long
                // enough to have sections at all.
                if (ruled) HorizontalDivider(
                    color = NxTheme.colors.outline.copy(alpha = 0.35f),
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
        "p", "figure" -> {
            // Image-only paragraph (badges / a banner / a video thumbnail, however
            // wrapped) -> clickable images. A paragraph that (invalidly, but as
            // Modrinth emits) holds block children -> flow them. Otherwise inline text.
            //
            // The element's OWN alignment counts, not just the one it inherited.
            // `<p align="center">` around a row of badges is how a description
            // centres them, and reading only the inherited flag left every one of
            // those rows hard against the left edge.
            val centred = center || align == TextAlign.Center
            val imgs = imageRunOf(el)
            when {
                imgs != null -> ImageRunBlock(imgs, onLink, center = centred)
                el.children().any { it.tagName().lowercase() in BLOCK_TAGS || it.tagName().equals("img", true) } ->
                    blocks(el, ctx, onLink, centred)
                else -> Text(
                    buildInline(el.childNodes(), ctx, onLink),
                    style    = TextStyle(color = ctx.baseColor, lineHeight = PROSE_LINE_HEIGHT, textAlign = align),
                    modifier = if (centred) Modifier.fillMaxWidth() else Modifier,
                )
            }
        }
        "ul", "ol" -> ListBlock(el, ctx, onLink, ordered = el.tagName().equals("ol", true))
        // A bar and indentation, no fill. A filled box reads as a callout -- a thing
        // the author marked as important -- and a quote is not that; it is the same
        // prose, set aside. The fill also stacked with a code block or a table
        // inside the quote, putting a surface on a surface.
        "blockquote" -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(NxTheme.colors.primary.copy(alpha = 0.55f)))
            Column(
                Modifier.padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(BLOCK_GAP),
            ) { blocks(el, ctx, onLink) }
        }
        // Scrolls sideways rather than wrapping. Wrapped code is code with its
        // structure taken out, and a line long enough to wrap is usually the one
        // being copied.
        "pre" -> Box(
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(NxTheme.colors.surface)
                .border(1.dp, bevelHairline(NxTheme.colors.surface), MaterialTheme.shapes.small)
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                el.wholeText().trimEnd(),
                style = TextStyle(color = ctx.baseColor, fontFamily = FontFamily.Monospace),
            )
        }
        "hr" -> HorizontalDivider(
            color = NxTheme.colors.outline.copy(alpha = 0.4f),
            modifier = Modifier.padding(vertical = 6.dp),
        )
        "img" -> ImageBlock(el)
        "table" -> TableBlock(el, ctx, onLink)
        "details" -> DetailsBlock(el, ctx, onLink)
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
    // Bare Text-in-columns read as loose text. What makes a grid legible is the
    // grid: an outer edge that says where the table ends, a header that is not
    // just bold text, banded rows so the eye holds a line across, and a rule
    // between columns -- without that last one two short cells beside each other
    // are indistinguishable from one cell with a space in it.
    // The rows of THIS table. A descendant query hoists a nested table's rows
    // into the outer one, where they are drawn as extra rows and again inside the
    // cell that holds the nested table -- the same content twice, in two shapes.
    val rows = remember(el) {
        el.select("tr").filter { row -> row.parents().firstOrNull { it.tagName().equals("table", true) } === el }
    }
    val shape = MaterialTheme.shapes.small
    val body = NxTheme.colors.surface
    val line = bevelHairline(body, 0.14f)
    val banded = bevelHairline(body, 0.05f)
    val headerBg = bevelHairline(body, 0.10f)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(body)
            .border(1.dp, line, shape),
    ) {
        rows.forEachIndexed { rowIdx, tr ->
            val cells = tr.children().filter { it.tagName().equals("td", true) || it.tagName().equals("th", true) }
            val header = cells.any { it.tagName().equals("th", true) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (header) headerBg else if (rowIdx % 2 == 0) body else banded)
                    .height(IntrinsicSize.Min),
            ) {
                cells.forEachIndexed { cellIdx, cell ->
                    Text(
                        buildInline(cell.childNodes(), ctx, onLink),
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 7.dp),
                        style = TextStyle(
                            color = ctx.baseColor,
                            lineHeight = PROSE_LINE_HEIGHT,
                            fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                    if (cellIdx < cells.lastIndex) Box(Modifier.width(1.dp).fillMaxHeight().background(line))
                }
            }
            if (rowIdx < rows.size - 1) HorizontalDivider(color = line)
        }
    }
}

/**
 * A `<details>` that actually folds.
 *
 * Both halves of it were rendered unconditionally before: the summary came out as
 * a stray line of text and the body it was meant to hide sat open underneath, so
 * a description that put its long tables and its spoilers behind a fold showed
 * all of them at once and the fold's label read as a heading nobody had styled.
 */
@Composable
private fun DetailsBlock(el: Element, ctx: InlineCtx, onLink: (String) -> Unit) {
    val summary = remember(el) { el.children().firstOrNull { it.tagName().equals("summary", true) } }
    // The summary is the control, so it must not also be rendered as content. A
    // detached copy keeps the parse tree untouched for anything else reading it.
    //
    // Its OWN summary, not every summary beneath it: a fold inside a fold is
    // ordinary in a description, and a descendant query strips the inner one's
    // label too, leaving it with a blank header.
    val inner = remember(el) {
        el.clone().also { copy ->
            copy.children().filter { it.tagName().equals("summary", true) }.forEach { it.remove() }
        }
    }
    var open by remember(el) { mutableStateOf(el.hasAttr("open")) }
    val shape = MaterialTheme.shapes.small
    val line = bevelHairline(NxTheme.colors.surface, 0.14f)
    Column(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, line, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bevelHairline(NxTheme.colors.surface, 0.10f))
                .clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Symbol(
                if (open) NxIcon.ExpandMore else NxIcon.ChevronRight,
                contentDescription = null,
                tint = ctx.baseColor,
                modifier = Modifier.size(18.dp),
            )
            Text(
                summary?.let { buildInline(it.childNodes(), ctx, onLink) } ?: AnnotatedString(""),
                style = TextStyle(color = ctx.baseColor, fontWeight = FontWeight.Bold),
            )
        }
        if (open) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(BLOCK_GAP),
            ) { blocks(inner, ctx, onLink) }
        }
    }
}

/**
 * A block image. The column bounds its width; nothing bounds its height.
 *
 * That is `height: auto`, and it is the only arrangement that agrees with the
 * source. A ceiling on the height silently decides the width too, and for
 * anything wider than it is tall that decision is a large reduction: against a
 * 560 ceiling a panel meant to fill the column was drawn two thirds of it, and
 * against the badge-row ceiling of 220 a 1920x977 screenshot was drawn 432 wide.
 *
 * [maxHeight] is left for the one case that genuinely wants it: a row of badges
 * stays a row only if no member of it can set the height of the rest.
 */
@Composable
private fun SizedImage(src: String, alt: String?, maxHeight: Dp?, modifier: Modifier = Modifier) {
    // Same gate the links get, for the same reason: a description is written by a
    // third party, and an image source is a fetch the page performs on its own.
    // A `file:` source would make remote text drive a local read.
    if (!isFetchableUrl(src)) return
    AsyncImage(
        model              = src,
        contentDescription = alt,
        contentScale       = ContentScale.Fit,
        modifier           = modifier
            .then(if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier)
            .clip(MaterialTheme.shapes.small),
    )
}

/** A row of badges stays a row; a tall one in it would set the height of the rest. */
private val BADGE_MAX_HEIGHT = 220.dp

@Composable
private fun ImageBlock(el: Element) {
    val src = el.attr("src").ifBlank { el.attr("data-src") }
    if (src.isBlank()) return
    // HTML width/height are CSS px, not dp -- honouring them as dp overflowed the
    // column (a width="660" banner blew past the content), so they are ignored.
    SizedImage(src, el.attr("alt").ifBlank { null }, maxHeight = null)
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
 *
 * A run of ONE is not a row -- it is a figure, and it is what a description means
 * by `![a screenshot](url)` on a line of its own. Held to the same height as a
 * strip of badges, every screenshot in a description came out a few hundred
 * pixels wide however large it really was, which is the only reason this
 * distinction exists.
 */
@Composable
private fun ImageRunBlock(items: List<ImgItem>, onLink: (String) -> Unit, center: Boolean = false) {
    val lone = items.singleOrNull()
    if (lone != null) {
        val href = lone.href
        val shape = MaterialTheme.shapes.small
        // Hover belongs to the picture, not to the line it sits on. Centred, the
        // clickable was stretched across the full width, so the cursor lit the
        // link up while nowhere near the image and the image itself never
        // answered. The inner box wraps its content, so it is exactly the photo.
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val ring by animateColorAsState(
            targetValue = if (hovered && href != null) NxTheme.colors.primary else Color.Transparent,
            animationSpec = tween(140),
            label = "figure-hover",
        )
        Box(
            modifier         = if (center) Modifier.fillMaxWidth() else Modifier,
            contentAlignment = if (center) Alignment.TopCenter else Alignment.TopStart,
        ) {
            Box(
                modifier = Modifier
                    .clip(shape)
                    .border(2.dp, ring, shape)
                    .then(
                        if (href != null) Modifier
                            .hoverable(interaction)
                            .clickable(interactionSource = interaction, indication = null) { onLink(href) }
                        else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                SizedImage(lone.src, lone.alt.ifBlank { null }, maxHeight = null)
                if (href != null && isPlayableVideoUrl(href)) {
                    Box(
                        modifier         = Modifier.size(48.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Symbol(NxIcon.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
        return
    }
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
                    modifier           = Modifier.heightIn(max = BADGE_MAX_HEIGHT),
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
                    // Underlined on hover, not at rest. A page of prose whose every
                    // link is permanently ruled reads as a page of corrections, and
                    // the underline is worth more as the answer to "is this the thing
                    // under my cursor" than as decoration that is always on.
                    if (href.isBlank()) kids() else withLink(
                        LinkAnnotation.Clickable(
                            tag = href,
                            styles = TextLinkStyles(
                                style = SpanStyle(color = ctx.linkColor),
                                focusedStyle = SpanStyle(color = ctx.linkColor, textDecoration = TextDecoration.Underline),
                                hoveredStyle = SpanStyle(color = ctx.linkColor, textDecoration = TextDecoration.Underline),
                                pressedStyle = SpanStyle(color = ctx.linkColor.copy(alpha = 0.75f), textDecoration = TextDecoration.Underline),
                            ),
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
