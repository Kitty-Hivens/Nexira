package hivens.ui.widgets.home.new

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.update.PackUpdateStatus
import hivens.core.update.PackUpdateStatusHub
import hivens.ui.AppState
import hivens.ui.Screen
import hivens.ui.components.rememberLaunchControl
import hivens.ui.effects.pixelArtBackground
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.PlayGround
import hivens.ui.nx.PlayLayout
import hivens.ui.screens.library.PendingUpdateBadge
import hivens.ui.screens.library.lastPlayedLabel
import hivens.ui.screens.library.rememberPackArt
import hivens.ui.screens.mod.loaderLabel
import hivens.ui.theme.NxColor
import hivens.ui.theme.decorativePair
import hivens.ui.theme.familyForText
import hivens.widget.model.Widget
import org.koin.compose.koinInject

// The library as spines on a shelf: every pack a strip the full height of the slot,
// side by side, and one of them opened out to the rest of the width.
//
// The pixel fill a pack without art falls back to reads as noise stretched over a
// banner and as texture down a tall narrow strip, so this is the arrangement where
// art-less packs look intended. The opened one is the pack to go back to until
// another is chosen: a click on a closed spine opens it, on a spring, and the one
// that was open closes as it does. The open pack's name goes to its page; Play
// launches it. As many spines stand as leave the open one room to say what it is.
@Widget(
    id = "home.new.spines",
    enter = "rise",
    displayName = "widget.home.new.spines",
    minWidth = 480, minHeight = 240,
    maxWidth = 2400, maxHeight = 1600,
)
@Composable
fun HomeNewSpines() {
    val repo: IPackRepository = koinInject()
    val all by remember { repo.observe() }.collectAsState()
    val packs = remember(all) {
        all.sortedWith(RecentFirst)
    }
    if (packs.isEmpty()) return
    var chosen by remember { mutableStateOf<String?>(null) }
    val open = packs.firstOrNull { it.id == chosen } ?: packs.first()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val count = spinesThatFit(maxWidth, packs.size)
        // The open pack always stands, whichever place it holds in the order.
        val shown = packs.take(count).let { if (open in it) it else it.dropLast(1) + open }
        val openWidth = maxWidth - (SPINE + GAP) * (shown.size - 1)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
            shown.forEach { pack ->
                val isOpen = pack.id == open.id
                val width by animateDpAsState(
                    targetValue   = if (isOpen) openWidth else SPINE,
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                )
                Spine(
                    pack     = pack,
                    open     = isOpen,
                    onChoose = { chosen = pack.id },
                    modifier = Modifier.width(width).fillMaxHeight(),
                )
            }
        }
    }
}

/** How many spines stand in [width]: the open one at its least, and closed ones in the room left. */
internal fun spinesThatFit(width: Dp, packs: Int): Int {
    val room = width - OPEN_MIN
    val closed = if (room <= 0.dp) 0 else ((room + GAP) / (SPINE + GAP)).toInt()
    return (1 + closed).coerceAtMost(packs).coerceAtLeast(1)
}

@Composable
private fun Spine(pack: PackInstance, open: Boolean, onChoose: () -> Unit, modifier: Modifier) {
    val ctx = LocalHomeNewContext.current
    val s = LocalStrings.current
    val art = rememberPackArt(pack)
    val (hueA, hueB) = decorativePair(pack.id)
    val shape = RoundedCornerShape(16.dp)
    // The open face fades in only once there is width to set it in, so the spring
    // never shows a name squeezed through a strip on its way out.
    val face by animateFloatAsState(if (open) 1f else 0f)
    Box(
        modifier
            .clip(shape)
            .clickable(enabled = !open, onClick = onChoose)
            .pixelArtBackground(pack.id, hueA, hueB),
    ) {
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
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.05f), 1f to Color.Black.copy(alpha = 0.85f)),
            ),
        )
        if (face < 1f) ClosedFace(pack, Modifier.align(Alignment.BottomCenter).alpha(1f - face))
        if (face > 0f) {
            val session = (ctx.appState as? AppState.Authenticated)?.session
            val control = rememberLaunchControl(pack, session)
            Column(
                Modifier.align(Alignment.BottomStart).padding(28.dp).alpha(face),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text  = if (pack.lastPlayedEpochOrZero > 0L) s.homeQuickContinue else s.homeQuickStart,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    PendingUpdateBadge(pack.id)
                }
                Text(
                    text       = pack.displayName,
                    fontFamily = familyForText(pack.displayName),
                    fontSize   = 48.sp,
                    lineHeight = 50.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color.White,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier.clickable { ctx.onScreenChange(Screen.PackDetail(pack.id)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    val hours = pack.playtimeSeconds / 3600
                    if (hours > 0) OnArtNumeral(s.homeFactHours(hours), s.homeFactPlaytime)
                    OnArtNumeral(lastPlayedLabel(pack.lastPlayedEpochOrZero), s.homeFactLastSession)
                    pack.cachedManifest?.takeIf { it.minecraftVersion.isNotBlank() }?.let { m ->
                        OnArtNumeral(m.minecraftVersion, loaderLabel(m.loaderName.ifBlank { "vanilla" }))
                    }
                }
                QuickLaunchButton(
                    quickLaunch = QuickLaunchTarget(pack, control),
                    ground      = PlayGround.Media,
                    layout      = PlayLayout.Plate,
                )
            }
        }
    }
}

/** A closed spine: the name up the strip, the hours under it, a dot when a build waits. */
@Composable
private fun ClosedFace(pack: PackInstance, modifier: Modifier) {
    val s = LocalStrings.current
    Column(modifier.padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text       = pack.displayName,
            fontFamily = familyForText(pack.displayName),
            fontSize   = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color      = Color.White,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
            modifier   = Modifier.upTheSpine(),
        )
        val hours = pack.playtimeSeconds / 3600
        if (hours > 0) {
            Spacer(Modifier.height(12.dp))
            Text(s.homeFactHours(hours), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f))
        }
        PendingDot(pack.id)
    }
}

/** A dot above the hours when the pack has a build waiting, the closed spine's version of the badge. */
@Composable
private fun PendingDot(packId: String) {
    val hub: PackUpdateStatusHub = koinInject()
    val statuses by hub.statuses.collectAsState()
    if (statuses[packId] !is PackUpdateStatus.Pending) return
    Spacer(Modifier.height(10.dp))
    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(NxColor.lead()))
}

@Composable
private fun OnArtNumeral(value: String, label: String) {
    Column {
        Text(value, fontSize = 30.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
    }
}

/** Sets text reading bottom to top, measured and placed as the strip it runs up. */
private fun Modifier.upTheSpine() = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints(maxWidth = constraints.maxHeight, maxHeight = constraints.maxWidth))
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer(
            x = -(placeable.width / 2 - placeable.height / 2),
            y = placeable.width / 2 - placeable.height / 2,
        ) { rotationZ = -90f }
    }
}

/** A closed spine. Wide enough for a name set up it at 22sp with room either side. */
private val SPINE = 96.dp
private val GAP = 10.dp

/** The least an open spine is given, so its name, its facts and Play still sit in it. */
private val OPEN_MIN = 420.dp
