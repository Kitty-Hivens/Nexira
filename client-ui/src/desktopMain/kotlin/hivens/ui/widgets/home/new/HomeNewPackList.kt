package hivens.ui.widgets.home.new

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.launch.LaunchControlMode
import hivens.ui.AppState
import hivens.ui.Screen
import hivens.ui.components.rememberLaunchControl
import hivens.ui.effects.pixelArtBackground
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxVerticalScrollbar
import hivens.ui.nx.PlayGround
import hivens.ui.nx.PlayLayout
import hivens.ui.screens.library.PendingUpdateBadge
import hivens.ui.screens.library.lastPlayedLabel
import hivens.ui.screens.library.rememberPackArt
import hivens.ui.feature.catalogue.project.loaderLabel
import hivens.ui.theme.NxInk
import hivens.ui.theme.decorativePair
import hivens.ui.theme.familyForText
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
data class PackListProps(
    @PropLabel("widget.home.new.packlist.title") val title: String = "",
    @PropLabel("widget.home.new.packlist.maxRows") @PropRange(0.0, 48.0) val maxRows: Int = 0,
    @PropLabel("widget.home.new.packlist.skipContinued") val skipContinued: Boolean = true,
)

// The library as a list, the way a player lists tracks: the pack's mark, its name,
// what it runs on, when it was last played, a waiting build if there is one, and the
// hours on the far edge. A list carries a library with no art far better than tiles
// do, because a row needs a mark the size of a thumbnail and not a picture.
//
// A row opens its pack. Play appears at the end of the row under the pointer, and
// stays while that pack is launching or running. By default the pack the continue
// widget is showing is left out, so the two do not say the same thing twice.
//
// Narrow, the facts that are least needed to choose a pack go first: when it was
// played, then what it runs on. The name and the hours stay.
//
// Every pack is listed unless the props name a ceiling. Where the slot bounds the
// height, as on a Home that does not scroll, the list scrolls inside it rather than
// dropping the rows that do not fit.
@Widget(
    id = "home.new.packlist",
    enter = "rise",
    displayName = "widget.home.new.packlist",
    propsClass = PackListProps::class,
    surface = """{"fill":"panel"}""",
    minWidth = 280, minHeight = 120,
    maxWidth = 2400, maxHeight = 1600,
)
@Composable
fun HomeNewPackList(instance: WidgetInstance) {
    val p = instance.rememberProps<PackListProps>()
    val ctx = LocalHomeNewContext.current
    val s = LocalStrings.current
    val repo: IPackRepository = koinInject()
    val all by remember { repo.observe() }.collectAsState()
    val continued = rememberQuickLaunchTarget()?.target

    // An empty library is where somebody starts, and this is the widget that says how.
    if (all.isEmpty()) {
        EmptyPacksCta(onBrowse = { ctx.onScreenChange(Screen.Browse) })
        return
    }

    val rows = remember(all, continued, p.skipContinued, p.maxRows) {
        all.sortedWith(RecentFirst)
            .filterNot { p.skipContinued && it.id == continued?.id }
            .let { if (p.maxRows > 0) it.take(p.maxRows) else it }
    }
    if (rows.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(
            text       = p.title.ifBlank { s.homeRecentTitle },
            style      = MaterialTheme.typography.titleSmall,
            color      = NxInk.main,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.padding(bottom = 6.dp),
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = PackListColumns.forWidth(maxWidth.value)
            ScrollingRows(bounded = maxHeight != Dp.Infinity) {
                rows.forEachIndexed { i, pack ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(RULE).background(NxInk.line))
                    PackRow(pack, columns)
                }
            }
        }
    }
}

/**
 * The rows, scrolling inside the height the slot leaves them when it leaves a bound.
 *
 * Unbounded, which is what a scrolling page hands down, there is nothing to scroll
 * against and a scroll measured against nothing throws, so the rows are laid out in
 * full and the page does the scrolling.
 *
 * The bar sits in the widget's side padding, clear of Play at the end of each row,
 * and is sized to the list rather than to the height on offer: filling the offer
 * stretched the panel to the bottom of the window under a list of two packs. The
 * position is saved, so Home opens where the list was left.
 */
@Composable
internal fun ScrollingRows(
    bounded: Boolean,
    state: ScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) },
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!bounded) {
        Column(content = content)
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(Modifier.fillMaxWidth().hoverable(interaction)) {
        Column(Modifier.fillMaxWidth().verticalScroll(state), content = content)
        Box(Modifier.matchParentSize()) {
            NxVerticalScrollbar(
                adapter  = rememberScrollbarAdapter(state),
                revealed = hovered || state.isScrollInProgress,
                modifier = Modifier.align(Alignment.CenterEnd).offset(x = BAR_OFFSET).fillMaxHeight(),
            )
        }
    }
}

/** Which of the optional columns a row of this width has room for. */
internal data class PackListColumns(val runsOn: Boolean, val played: Boolean) {
    companion object {
        fun forWidth(widthDp: Float) = PackListColumns(
            runsOn = widthDp >= RUNS_ON_FROM,
            played = widthDp >= PLAYED_FROM,
        )

        private const val RUNS_ON_FROM = 520f
        private const val PLAYED_FROM = 720f
    }
}

@Composable
private fun PackRow(pack: PackInstance, columns: PackListColumns) {
    val ctx = LocalHomeNewContext.current
    val s = LocalStrings.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val session = (ctx.appState as? AppState.Authenticated)?.session
    val control = rememberLaunchControl(pack, session)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .background(NxInk.main.copy(alpha = if (hovered) HOVER_ALPHA else 0f))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { ctx.onScreenChange(Screen.PackDetail(pack.id)) }
            .padding(horizontal = 8.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PackMark(pack, Modifier.size(MARK))
        Text(
            text       = pack.displayName,
            fontFamily = familyForText(pack.displayName),
            color      = NxInk.main,
            fontWeight = FontWeight.Medium,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
            modifier   = Modifier.weight(2f),
        )
        if (columns.runsOn) {
            Text(
                text     = pack.cachedManifest?.let { m ->
                    listOfNotNull(
                        m.minecraftVersion.takeIf { it.isNotBlank() },
                        m.loaderName.takeIf { it.isNotBlank() && !it.equals("vanilla", ignoreCase = true) }?.let(::loaderLabel),
                    ).joinToString(" · ")
                }.orEmpty(),
                style    = MaterialTheme.typography.bodySmall,
                color    = NxInk.quiet,
                maxLines = 1,
                modifier = Modifier.weight(1.4f),
            )
        }
        if (columns.played) {
            Text(
                text     = lastPlayedLabel(pack.lastPlayedEpochOrZero),
                style    = MaterialTheme.typography.bodySmall,
                color    = NxInk.quiet,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        Box(Modifier.weight(1.2f)) {
            PendingUpdateBadge(pack.id, tone = NxMetaChipTone.Surface)
        }
        val hours = pack.playtimeSeconds / 3600
        Text(
            text     = if (hours > 0) s.homeFactHours(hours) else "",
            style    = MaterialTheme.typography.bodyMedium,
            color    = NxInk.quiet,
            maxLines = 1,
            modifier = Modifier.width(HOURS_WIDTH),
        )
        RowPlay(QuickLaunchTarget(pack, control), visible = hovered || control.mode != LaunchControlMode.Play)
    }
}

/** Play at the end of a row: under the pointer, or while its pack is under way. */
@Composable
private fun RowPlay(quickLaunch: QuickLaunchTarget, visible: Boolean) {
    Box(Modifier.width(PLAY_WIDTH), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            QuickLaunchButton(
                quickLaunch = quickLaunch,
                ground      = PlayGround.Surface,
                layout      = PlayLayout.Plate,
                iconOnly    = true,
            )
        }
    }
}

/** The pack's icon when it has one, a square of its fill when it does not. */
@Composable
private fun PackMark(pack: PackInstance, modifier: Modifier) {
    val art = rememberPackArt(pack)
    val (hueA, hueB) = decorativePair(pack.id)
    val shape = RoundedCornerShape(8.dp)
    if (art.iconUrl != null) {
        AsyncImage(
            model              = art.iconUrl,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = modifier.clip(shape),
        )
    } else {
        Box(modifier.clip(shape).pixelArtBackground(pack.id, hueA, hueB)) { Box(Modifier.fillMaxSize()) }
    }
}

private val ROW_HEIGHT = 52.dp
private val RULE = 1.dp
private val MARK = 32.dp
private val HOURS_WIDTH = 64.dp
private val PLAY_WIDTH = 40.dp
private const val HOVER_ALPHA = 0.05f

/** Into the 20dp side padding, so the 8dp bar stands 6dp off the panel's edge. */
private val BAR_OFFSET = 14.dp
