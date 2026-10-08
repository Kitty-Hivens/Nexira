package hivens.ui.widgets.mod

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import hivens.ui.components.LoaderGlyph
import hivens.ui.components.hasLoaderGlyph
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxTooltip
import coil3.compose.AsyncImage
import hivens.ui.screens.mod.ProjectCreator
import hivens.ui.screens.mod.rememberLinkFollower
import hivens.ui.screens.mod.ProjectLinkKind
import hivens.core.api.dto.modrinth.ModrinthDisclosure
import hivens.ui.screens.mod.disclosureIsWarning
import hivens.ui.screens.mod.disclosuresInReadingOrder
import hivens.ui.screens.mod.disclosureLine
import hivens.ui.screens.mod.Environment
import hivens.ui.screens.mod.OpenProject
import hivens.ui.screens.browse.ProjectTag
import hivens.ui.screens.browse.tagSearch
import hivens.ui.puppet.PuppetClick
import hivens.ui.screens.mod.ProjectSource
import hivens.ui.screens.mod.environmentLabel
import hivens.ui.screens.mod.environments
import hivens.ui.screens.mod.licenseLabel
import hivens.ui.screens.mod.linkLabel
import hivens.ui.screens.mod.loaderDot
import hivens.ui.screens.mod.loaderLabel
import hivens.ui.theme.decorativeColor
import hivens.ui.utils.humanSize
import hivens.ui.widgets.RailBlock
import hivens.ui.widgets.RailGroup
import hivens.ui.widgets.RailLabel
import hivens.ui.widgets.Sources
import hivens.widget.api.rememberSource
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.theme.Status

/**
 * The project page's metadata blocks, which live in the right rail rather than in
 * the page.
 *
 * `appshell.rightrail` is a shell surface and is present on every screen, so a
 * page carrying its own right-hand column would stand a third column next to the
 * news. The blocks go here instead and the page keeps the header, the tabs and
 * the body.
 *
 * Each one reads [Sources.OpenProject] and draws nothing when it is null, which
 * is every screen but the page. That is what makes them ordinary widgets: the
 * reader can move them between the rail's two project slots, or take one out, and
 * nothing anywhere has to know they are gone.
 */

/**
 * A file the catalogue cannot answer for still gets the question asked. While the
 * page is still asking, the place holds an ellipsis instead.
 */
@Composable
private fun unknown(p: OpenProject) = if (p.pending) PENDING else LocalStrings.current.modRailUnknownValue

/** What a fact reads while the page has not heard back. */
private const val PENDING = "…"

@Widget(
    id = "mod.compatibility",
    displayName = "widget.mod.compatibility",
    // Each block is a CARD, which is what the reference draws and what the first
    // pass here missed entirely: bare text stacked on the rail's own plane, with
    // nothing saying where one block ended and the next began. One step up off the
    // rail and a hairline; the corner is left unsaid so it takes our own card token
    // rather than pinning theirs.
    //
    // No padding here. The record's padding is an OUTER inset, applied before the
    // plane so the rounding hugs the widget rather than its margin -- putting the
    // card's sixteen in it shrank every card by thirty-two, pushed the text flat
    // against the border, and turned the twelve between cards into forty-four. The
    // inside of a block belongs to the block.
    surface = """{"fill":"card","border":{"widthDp":1.0}}""",
)
@Composable
fun ProjectCompatibilityWidget(instance: WidgetInstance) {
    val project by rememberSource(Sources.OpenProject)
    val p = project ?: return
    val s = LocalStrings.current

    RailBlock(s.modRailCompatibility) {
        RailGroup {
        RailLabel(s.modRailGame)
        // A jar declares the loader it needs and usually the game version it was
        // built against, so those two survive the loss of the catalogue. What it
        // cannot tell us is the RANGE it also runs on, and a range guessed from
        // one build would be a lie with a confident shape.
        if (p.gameVersions.isEmpty()) {
            Chips(listOf(unknown(p)))
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                p.gameVersions.forEach { group ->
                    val tag = ProjectTag.GameVersions(group.versions)
                    NxMetaChip(group.label, tone = NxMetaChipTone.Surface, onClick = tagSearch(p.projectType, p.packId, tag))
                    TagPuppet("mod.version.${group.label}", p, tag)
                }
            }
        }
        }

        RailGroup {
        RailLabel(s.modRailPlatforms)
        if (p.loaders.isEmpty()) {
            Chips(listOf(unknown(p)))
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                p.loaders.forEach { loader ->
                    val tag = ProjectTag.Loader(loader)
                    // The project's own mark where there is one, and the colour
                    // swatch only where there is not: a launcher that lists what a
                    // mod runs on is naming the loaders, and a coloured square is a
                    // worse answer to "is this the Fabric one" than the logo is.
                    NxMetaChip(
                        loaderLabel(loader),
                        dot = loaderDot(loader).takeUnless { hasLoaderGlyph(loader) },
                        leading = if (hasLoaderGlyph(loader)) {
                            { LoaderGlyph(loader, tint = NxInk.quiet, size = 12.dp) }
                        } else {
                            null
                        },
                        onClick = tagSearch(p.projectType, p.packId, tag),
                    )
                    TagPuppet("mod.loader.$loader", p, tag)
                }
            }
        }
        }

        // One chip per true statement, not one phrase for the pair. A mod required
        // on the client and optional on the server is both client-side and
        // server-capable, and a single word has to throw one of those away.
        val envs = environments(p.clientSide, p.serverSide)
        if (envs.isNotEmpty() || p.source == ProjectSource.Local) {
            RailGroup {
            RailLabel(s.modRailEnvironment)
            if (envs.isEmpty()) {
                Chips(listOf(unknown(p)))
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    envs.forEach { env ->
                        val tag = ProjectTag.Runs(env)
                        NxMetaChip(
                            environmentLabel(env, s),
                            tone = NxMetaChipTone.Surface,
                            leading = {
                                Symbol(
                                    environmentIcon(env),
                                    contentDescription = null,
                                    tint = NxInk.quiet,
                                    size = 12.dp,
                                )
                            },
                            onClick = tagSearch(p.projectType, p.packId, tag),
                        )
                        TagPuppet("mod.env.${env.name}", p, tag)
                    }
                }
            }
            }
        }
    }
}

private fun environmentIcon(environment: Environment): IconKey = when (environment) {
    Environment.Client -> NxIcon.Computer
    Environment.Server -> NxIcon.Storage
    Environment.Both -> NxIcon.Lan
}

/**
 * The project's own tags.
 *
 * Its own block, between the links and the people. The header carries the title
 * and the tagline and has no room to also say what kind of thing this is, and
 * three categories crammed under a description read as an afterthought rather
 * than as the project's own classification.
 */
@Widget(
    id = "mod.tags",
    displayName = "widget.mod.tags",
    // Each block is a CARD, which is what the reference draws and what the first
    // pass here missed entirely: bare text stacked on the rail's own plane, with
    // nothing saying where one block ended and the next began. One step up off the
    // rail and a hairline; the corner is left unsaid so it takes our own card token
    // rather than pinning theirs.
    //
    // No padding here. The record's padding is an OUTER inset, applied before the
    // plane so the rounding hugs the widget rather than its margin -- putting the
    // card's sixteen in it shrank every card by thirty-two, pushed the text flat
    // against the border, and turned the twelve between cards into forty-four. The
    // inside of a block belongs to the block.
    surface = """{"fill":"card","border":{"widthDp":1.0}}""",
)
@Composable
fun ProjectTagsWidget(instance: WidgetInstance) {
    val project by rememberSource(Sources.OpenProject)
    val p = project ?: return
    val s = LocalStrings.current
    if (p.categories.isEmpty()) return

    RailBlock(s.modRailTags) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            p.categories.forEach { name ->
                val tag = ProjectTag.Category(name)
                NxMetaChip(s.modrinthCategory(name), tone = NxMetaChipTone.Surface, onClick = tagSearch(p.projectType, p.packId, tag))
                TagPuppet("mod.tag.$name", p, tag)
            }
        }
    }
}

@Widget(
    id = "mod.links",
    displayName = "widget.mod.links",
    // Each block is a CARD, which is what the reference draws and what the first
    // pass here missed entirely: bare text stacked on the rail's own plane, with
    // nothing saying where one block ended and the next began. One step up off the
    // rail and a hairline; the corner is left unsaid so it takes our own card token
    // rather than pinning theirs.
    //
    // No padding here. The record's padding is an OUTER inset, applied before the
    // plane so the rounding hugs the widget rather than its margin -- putting the
    // card's sixteen in it shrank every card by thirty-two, pushed the text flat
    // against the border, and turned the twelve between cards into forty-four. The
    // inside of a block belongs to the block.
    surface = """{"fill":"card","border":{"widthDp":1.0}}""",
)
@Composable
fun ProjectLinksWidget(instance: WidgetInstance) {
    val project by rememberSource(Sources.OpenProject)
    val p = project ?: return
    val s = LocalStrings.current
    val follow = rememberLinkFollower()
    if (p.links.isEmpty()) return

    RailBlock(s.modRailLinks) {
        val donations = p.links.count { it.kind == ProjectLinkKind.Donate }
        p.links.forEachIndexed { index, link ->
            // Where the author can be reached, then a rule, then where they can be
            // paid. Two different asks, and running them together made the first
            // donation row read as one more support channel.
            if (donations in 1 until p.links.size && link.kind == ProjectLinkKind.Donate &&
                p.links.getOrNull(index - 1)?.kind != ProjectLinkKind.Donate
            ) {
                HorizontalDivider(color = NxInk.line.copy(alpha = 0.25f))
            }
            // Underline on hover, no plate behind it. A link is text, and a filled
            // rectangle appearing under a line of text reads as a row in a list
            // rather than as the word you are about to follow. The reference
            // underlines for exactly this reason.
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Row(
                modifier = Modifier.fillMaxWidth()
                    .hoverable(interaction)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                    ) { follow(link.url) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Symbol(linkIcon(link.kind), contentDescription = null, tint = NxInk.quiet, size = 16.dp)
                Text(
                    linkLabel(link, s),
                    style = MaterialTheme.typography.bodySmall,
                    color = NxInk.main,
                    textDecoration = if (hovered) TextDecoration.Underline else null,
                )
                Symbol(NxIcon.OpenInNew, contentDescription = null, tint = NxInk.quiet, size = 12.dp)
            }
        }
    }
}

private fun linkIcon(kind: ProjectLinkKind): IconKey = when (kind) {
    ProjectLinkKind.Issues -> NxIcon.BugReport
    ProjectLinkKind.Source -> NxIcon.Code
    ProjectLinkKind.Wiki -> NxIcon.Description
    ProjectLinkKind.Discord -> NxIcon.Language
    ProjectLinkKind.Donate -> NxIcon.Favorite
    ProjectLinkKind.Page -> NxIcon.Public
}

/**
 * Who is credited on the project, owner first.
 *
 * A block of people, not a line of names: the catalogue puts a face and a role
 * beside each one because "who wrote this" is a question a reader asks about an
 * unfamiliar mod before they ask anything else, and a comma-separated string is
 * the shape that answer takes when nobody wanted to build the block.
 */
@Widget(
    id = "mod.creators",
    displayName = "widget.mod.creators",
    // Each block is a CARD, which is what the reference draws and what the first
    // pass here missed entirely: bare text stacked on the rail's own plane, with
    // nothing saying where one block ended and the next began. One step up off the
    // rail and a hairline; the corner is left unsaid so it takes our own card token
    // rather than pinning theirs.
    //
    // No padding here. The record's padding is an OUTER inset, applied before the
    // plane so the rounding hugs the widget rather than its margin -- putting the
    // card's sixteen in it shrank every card by thirty-two, pushed the text flat
    // against the border, and turned the twelve between cards into forty-four. The
    // inside of a block belongs to the block.
    surface = """{"fill":"card","border":{"widthDp":1.0}}""",
)
@Composable
fun ProjectCreatorsWidget(instance: WidgetInstance) {
    val project by rememberSource(Sources.OpenProject)
    val p = project ?: return
    val s = LocalStrings.current
    if (p.creators.isEmpty()) return

    RailBlock(s.modRailCreators) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            p.creators.forEach { creator ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CreatorAvatar(creator)
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                creator.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = NxInk.main,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // The owner is marked rather than sorted into place and
                            // left to be inferred from being first.
                            if (creator.owner) {
                                Symbol(
                                    NxIcon.Star,
                                    contentDescription = null,
                                    tint = NxColor.status(Status.Warning),
                                    size = 12.dp,
                                )
                            }
                        }
                        Text(
                            creator.role,
                            style = MaterialTheme.typography.labelSmall,
                            color = NxInk.quiet,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatorAvatar(creator: ProjectCreator) {
    val shape = CircleShape
    if (creator.avatarUrl != null) {
        AsyncImage(
            model = creator.avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(28.dp).clip(shape),
        )
    } else {
        Box(
            Modifier.size(28.dp).clip(shape).background(decorativeColor(creator.name)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                creator.name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Widget(
    id = "mod.details",
    displayName = "widget.mod.details",
    // Each block is a CARD, which is what the reference draws and what the first
    // pass here missed entirely: bare text stacked on the rail's own plane, with
    // nothing saying where one block ended and the next began. One step up off the
    // rail and a hairline; the corner is left unsaid so it takes our own card token
    // rather than pinning theirs.
    //
    // No padding here. The record's padding is an OUTER inset, applied before the
    // plane so the rounding hugs the widget rather than its margin -- putting the
    // card's sixteen in it shrank every card by thirty-two, pushed the text flat
    // against the border, and turned the twelve between cards into forty-four. The
    // inside of a block belongs to the block.
    surface = """{"fill":"card","border":{"widthDp":1.0}}""",
)
@Composable
fun ProjectDetailsWidget(instance: WidgetInstance) {
    val project by rememberSource(Sources.OpenProject)
    val p = project ?: return
    val s = LocalStrings.current

    RailBlock(s.modRailDetails) {
        // Declarations come FIRST and in plain text. Only the one that can
        // physically hurt someone gets a colour: the difference between informing
        // a reader and accusing an author.
        disclosuresInReadingOrder(p.disclosures).forEach { d ->
            val (headline, notes) = disclosureLine(d, s)
            val warning = disclosureIsWarning(d)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Symbol(
                    disclosureIcon(d.type),
                    contentDescription = null,
                    tint = if (warning) NxColor.status(Status.Error) else NxInk.quiet,
                    size = 16.dp,
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        headline,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (warning) NxColor.status(Status.Error) else NxInk.main,
                    )
                    notes.forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = NxInk.quiet)
                    }
                }
            }
        }

        // The jar carries its own licence; the dates belong to the catalogue
        // entry rather than to the file, so a local one says it does not know
        // instead of showing the file's mtime as if it meant something.
        if (p.answersLicence) {
            Fact(NxIcon.Shield, if (p.pending) PENDING else licenseLabel(p.licenseId, p.licenseName, s))
        }
        // How long ago in the line, the exact moment on hover. A date column that
        // spells out the timestamp makes the reader do the arithmetic on every row.
        if (p.answersPublished) {
            DatedFact(
                NxIcon.NewReleases,
                p.publishedAt?.let { s.modPublishedOn(it) } ?: if (p.pending) PENDING else s.modPublishedUnknown,
                p.publishedExact,
            )
        }
        if (p.answersUpdated) {
            DatedFact(
                NxIcon.Update,
                p.updatedAt?.let { s.modUpdatedOn(it) } ?: if (p.pending) PENDING else s.modUpdatedUnknown,
                p.updatedExact,
            )
        }

        // What a pack asks of the machine and of the player, before what it holds.
        // The sign-in comes first of the three: it is the one that stops a game
        // from starting.
        p.signIn.forEach { Fact(NxIcon.VpnKey, s.packRailSignIn(it)) }
        p.runtime?.let { Fact(NxIcon.Memory, it) }
        p.modsCount?.let { Fact(NxIcon.Widgets, "${s.packRailMods}: $it") }

        // What only the file can answer. These three were the whole of the
        // read-only dialog the page replaces, so they follow it here rather than
        // being lost on the way: a reader who used to check a jar's dependencies
        // must not find that the richer surface tells them less.
        p.sizeBytes?.let { Fact(NxIcon.InsertDriveFile, "${s.contentDetailSize}: ${humanSize(it, s)}") }
        if (p.authors.isNotEmpty()) {
            Fact(NxIcon.Person, "${s.contentDetailAuthors}: ${p.authors.joinToString(", ")}")
        }
        if (p.usedBy.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Fact(NxIcon.Inventory2, s.packRailUsedBy)
                Text(
                    p.usedBy.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = NxInk.quiet,
                )
            }
        }
        if (p.dependencies.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Fact(NxIcon.Lan, s.contentTabModDependencies(p.dependencies.size))
                Text(
                    p.dependencies.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = NxInk.quiet,
                )
            }
        }
    }
}

/**
 * An icon per declaration, and a deliberately narrow set.
 *
 * Info standing in for a megaphone, a coin and a radio tower alike was the gap
 * the mock-up found. These three are the ones a reader scans for; the rest keep
 * the neutral mark rather than getting a symbol invented for them.
 */
private fun disclosureIcon(type: String): IconKey = when (type) {
    ModrinthDisclosure.TELEMETRY -> NxIcon.Wifi
    ModrinthDisclosure.ADVERTISEMENTS -> NxIcon.Campaign
    ModrinthDisclosure.PAID_FEATURES -> NxIcon.Paid
    ModrinthDisclosure.EPILEPSY_TRIGGERS -> NxIcon.Visibility
    ModrinthDisclosure.AI_CONTENT, ModrinthDisclosure.AI_FUNCTIONALITY -> NxIcon.Science
    ModrinthDisclosure.SYSTEM_INTERACTIONS -> NxIcon.Computer
    ModrinthDisclosure.DERIVATIVE_WORK -> NxIcon.Code
    else -> NxIcon.Info
}

// ── Block furniture of its own ─────────────────────────────────────────

@Composable
private fun Chips(values: List<String>) = FlowRow(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
) { values.forEach { NxMetaChip(it, tone = NxMetaChipTone.Surface) } }

/** A tag's search, reachable by a driver without a pointer. */
@Composable
private fun TagPuppet(id: String, p: OpenProject, tag: ProjectTag) {
    val open = tagSearch(p.projectType, p.packId, tag) ?: return
    PuppetClick(id) { open() }
}

/** A fact whose precise form is a hover away. */
@Composable
private fun DatedFact(icon: IconKey, text: String, exact: String?) {
    if (exact == null) {
        Fact(icon, text)
    } else {
        NxTooltip(text = exact) { Fact(icon, text) }
    }
}

@Composable
private fun Fact(icon: IconKey, text: String) = Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(9.dp),
) {
    Symbol(icon, contentDescription = null, tint = NxInk.quiet, size = 16.dp)
    Text(text, style = MaterialTheme.typography.bodySmall, color = NxInk.main)
}
