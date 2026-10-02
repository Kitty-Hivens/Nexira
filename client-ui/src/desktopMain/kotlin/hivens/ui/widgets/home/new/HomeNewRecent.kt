package hivens.ui.widgets.home.new

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import hivens.core.launch.LaunchControlMode
import hivens.ui.AppState
import hivens.ui.components.rememberLaunchControl
import hivens.ui.nx.PlayGround
import hivens.ui.nx.PlayLayout
import hivens.ui.screens.library.PendingUpdateBadge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.ui.Screen
import hivens.ui.effects.pixelArtBackground
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.screens.library.lastPlayedLabel
import hivens.ui.screens.library.rememberPackArt
import hivens.ui.screens.mod.loaderLabel
import hivens.ui.theme.familyForText
import hivens.ui.theme.decorativePair
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor

@Serializable
data class RecentProps(
    @PropLabel("widget.home.new.recent.title") val title: String = "",
    /** A ceiling on top of what fits. Zero is none: the rows decide. */
    @PropLabel("widget.home.new.recent.maxTiles") @PropRange(0.0, 48.0) val maxTiles: Int = 0,
    @PropLabel("widget.home.new.recent.rows") @PropRange(1.0, 6.0) val rows: Int = 1,
    /** The narrowest a tile may get. Tiles stretch from here to fill the row. */
    @PropLabel("widget.home.new.recent.tileWidth") @PropRange(160.0, 480.0) val tileWidth: Int = 220,
)

// Pack tiles. Sort priority: played packs first by recency, then unplayed packs by
// install order. A fresh install with packs but no launches still shows the tiles
// (sorted by createdAt), so Home reads as populated rather than blank. Empty repo
// shows a CTA pointing at Browse.
//
// The tiles fill the row. A row of fixed tiles took half the width of a 1080p window
// and less than half of a 1440p one, and the rest of the panel was a bar with nothing
// on it. Now as many columns as fit at the tile's narrowest, stretched to the edge,
// and the person picks how many rows: one keeps the wallpaper below in view, more
// make the surface a launcher grid.
//
// A panel, declared rather than drawn here, so the editor's surface rows move this
// one. The row's title and the empty state are text, and text laid straight on the
// page sits on whatever the wallpaper has there, which no theme can answer for.
@Widget(
    id = "home.new.recent",
    enter = "rise",
    displayName = "widget.home.new.recent",
    propsClass = RecentProps::class,
    surface = """{"fill":"panel"}""",
    minWidth = 240, minHeight = 120,
    maxWidth = 2400, maxHeight = 1600,
)
@Composable
fun HomeNewRecent(instance: WidgetInstance) {
    val p = instance.rememberProps<RecentProps>()
    val ctx = LocalHomeNewContext.current
    val s = LocalStrings.current
    val repo: IPackRepository = koinInject()
    val all by remember { repo.observe() }.collectAsState()

    if (all.isEmpty()) {
        EmptyPacksCta(onBrowse = { ctx.onScreenChange(Screen.Browse) })
        return
    }

    val sorted = remember(all) {
        all.sortedWith(
            compareByDescending<PackInstance> { it.lastPlayedEpochOrZero }
                .thenByDescending { it.createdAtEpoch },
        )
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text       = p.title.ifBlank { s.homeRecentTitle },
            style      = MaterialTheme.typography.titleSmall,
            color      = NxInk.main,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.padding(bottom = 10.dp),
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fit = tileColumns(maxWidth, p.tileWidth.dp)
            val shown = sorted.take(tileBudget(fit, p.rows, p.maxTiles))
            // Fewer packs than columns: the ones there are share the row, so it does
            // not end on empty cells, up to a stretch past which two packs would be
            // two posters.
            val columns = fit.coerceAtMost(shown.size).coerceAtLeast(1)
            val tileWidth = ((maxWidth - TILE_GAP * (columns - 1)) / columns).coerceAtMost(p.tileWidth.dp * MAX_STRETCH)
            Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                shown.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                        row.forEach { pack ->
                            PackTile(
                                pack     = pack,
                                onClick  = { ctx.onScreenChange(Screen.PackDetail(pack.id)) },
                                modifier = Modifier.width(tileWidth).aspectRatio(TILE_ASPECT),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val TILE_GAP = 10.dp

/** How far past its narrowest a tile may stretch to fill a row it has too few neighbours for. */
private const val MAX_STRETCH = 1.8f

/** Width over height. Wide enough for a banner to read as one, tall enough for two lines under it. */
private const val TILE_ASPECT = 16f / 9f

/** How many tiles of at least [minTile] fit across [width], with the gaps between them. */
internal fun tileColumns(width: Dp, minTile: Dp): Int =
    (((width + TILE_GAP) / (minTile + TILE_GAP)).toInt()).coerceAtLeast(1)

/** How many tiles the grid holds: the rows asked for, under a ceiling when one is named. */
internal fun tileBudget(columns: Int, rows: Int, maxTiles: Int): Int {
    val grid = columns * rows.coerceAtLeast(1)
    return if (maxTiles > 0) minOf(grid, maxTiles) else grid
}

// Mini version of the Library card's three-layer treatment: pixel-art fill,
// captured banner when the pack has one, scrim, caption. The caption says what the
// pack runs on and when it was last played, which is what picking one off Home
// needs, rather than its catalogue id.
//
// It answers the pointer the way a mouse target does on iPadOS: it lifts, a little
// larger with a shadow under it, on a spring that overshoots once, and offers Play
// in its corner. The click still opens the pack; Play is the one control on it. A
// launch that is under way keeps its control showing, so a tile does not hide
// that its pack is starting the moment the pointer leaves.
@Composable
private fun PackTile(pack: PackInstance, onClick: () -> Unit, modifier: Modifier) {
    val ctx = LocalHomeNewContext.current
    val (hueA, hueB) = decorativePair(pack.id)
    val art = rememberPackArt(pack)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lift by animateFloatAsState(
        targetValue   = if (hovered) 1f else 0f,
        animationSpec = spring(dampingRatio = LIFT_DAMPING, stiffness = Spring.StiffnessMediumLow),
    )
    val session = (ctx.appState as? AppState.Authenticated)?.session
    val control = rememberLaunchControl(pack, session)
    val runsOn = pack.cachedManifest?.let { m ->
        listOfNotNull(
            m.minecraftVersion.takeIf { it.isNotBlank() },
            m.loaderName.takeIf { it.isNotBlank() && !it.equals("vanilla", ignoreCase = true) }?.let(::loaderLabel),
        ).joinToString(" · ")
    }
    val caption = listOfNotNull(runsOn?.takeIf { it.isNotBlank() }, lastPlayedLabel(pack.lastPlayedEpochOrZero))
        .joinToString(" · ")
    val shape = MaterialTheme.shapes.medium
    BoxWithConstraints(
        modifier = modifier
            .zIndex(if (hovered) 1f else 0f)
            .graphicsLayer {
                val scale = 1f + LIFT_SCALE * lift
                scaleX = scale
                scaleY = scale
                shadowElevation = LIFT_SHADOW.toPx() * lift.coerceAtLeast(0f)
                this.shape = shape
                clip = true
            }
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        val roomy = maxWidth >= ROOMY_TILE
        Box(Modifier.fillMaxSize().pixelArtBackground(pack.id, hueA, hueB))
        if (art.bannerUrl != null) {
            AsyncImage(
                model              = art.bannerUrl,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.05f),
                    1f to Color.Black.copy(alpha = 0.72f),
                ),
            ),
        )
        PendingUpdateBadge(pack.id, Modifier.align(Alignment.TopStart).padding(8.dp))
        AnimatedVisibility(
            visible  = hovered || control.mode != LaunchControlMode.Play,
            enter    = fadeIn() + scaleIn(initialScale = 0.8f),
            exit     = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        ) {
            QuickLaunchButton(
                quickLaunch = QuickLaunchTarget(pack, control),
                ground      = PlayGround.Media,
                layout      = PlayLayout.Plate,
                iconOnly    = true,
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(if (roomy) 16.dp else 12.dp),
        ) {
            Text(
                text       = pack.displayName,
                fontFamily = familyForText(pack.displayName),
                style      = if (roomy) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                color      = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Text(
                text     = caption,
                style    = if (roomy) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
                color    = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How much larger a tile gets under the pointer. Small: it is a lift, not a zoom. */
private const val LIFT_SCALE = 0.04f

/** Under one, so the lift overshoots once and settles, which is what makes it read as physical. */
private const val LIFT_DAMPING = 0.55f

private val LIFT_SHADOW = 14.dp

/** From this width a tile sets its name a size up, so a large tile is not a large picture with small print. */
private val ROOMY_TILE = 300.dp

@Composable
internal fun EmptyPacksCta(onBrowse: () -> Unit) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text       = s.homeNoPacksTitle,
            style      = MaterialTheme.typography.titleSmall,
            color      = NxInk.main,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text  = s.homeNoPacksBody,
            style = MaterialTheme.typography.bodySmall,
            color = NxInk.quiet,
        )
        Spacer(Modifier.height(2.dp))
        OutlinedButton(
            onClick = onBrowse,
            shape   = MaterialTheme.shapes.small,
            colors  = ButtonDefaults.outlinedButtonColors(
                contentColor = NxColor.lead(),
            ),
        ) {
            Symbol(NxIcon.Search, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(s.browseOpen, fontWeight = FontWeight.Medium)
        }
    }
}
