package hivens.ui.activity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import hivens.core.activity.Activity
import hivens.core.activity.ActivityAction
import hivens.core.activity.ActivityPhase
import hivens.core.activity.ActivityRegistry
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxProgressBar
import hivens.ui.surface.FrostTier
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativeColor
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/** How the measure is drawn. See the design review for what each costs. */
@Serializable
enum class PillProgress { Edge, Bar, Fill, Ring, None }

/** Which edge of the content column the pill grows from. */
@Serializable
enum class PillAnchor { Left, Center }

@Serializable
data class PillProps(
    @PropLabel("widget.activity.pill.progress") val progress: PillProgress = PillProgress.Edge,
    @PropLabel("widget.activity.pill.anchor") val anchor: PillAnchor = PillAnchor.Center,
    // Flat rather than Clear: Clear is a bodiless glass coat, which reads over a
    // wallpaper and disappears over the near-black default ground.
    @PropLabel("widget.appshell.region.frostTier") val frostTier: FrostTier = FrostTier.Heavy,
    @PropLabel("widget.activity.pill.heightDp") @PropRange(40.0, 76.0) val heightDp: Int = 58,
    @PropLabel("widget.appshell.region.collapsed") val collapsed: Boolean = false,
    @PropLabel("widget.activity.pill.showActions") val showActions: Boolean = true,
)

/**
 * The launcher's one account of what it is doing, as a floating object over the
 * content column.
 *
 * It is not a region in the shell's Column. A strip in the layout flow costs its
 * height for the whole session and reflows the body when it appears; floating
 * costs nothing at rest and moves nothing when it arrives. That is also why the
 * anchor matters: the left edge is pinned to the subject icon, so a title that
 * changes length while a job runs moves the right edge, away from where the eye
 * is resting.
 *
 * The object is born once and then changes, rather than appearing per event. A
 * circle and a pill are the same shape at two widths, so the unfurl animates one
 * number and the content -- laid out at full width and only clipped -- reveals
 * itself in reading order with no timeline of its own.
 */
@Widget(
    id = "appshell.activity.pill",
    displayName = "widget.activity.pill",
    propsClass = PillProps::class,
)
@Composable
fun ActivityPillWidget(instance: WidgetInstance) {
    val props = instance.rememberProps<PillProps>()
    val registry: ActivityRegistry = koinInject()
    val commands: ActivityCommands = koinInject()
    val activities by registry.activities.collectAsState()
    val style = LocalStyle.current
    val s = LocalStrings.current
    // Resolved here: the enter/exit spec lambdas are not composable.
    val ballPx = with(LocalDensity.current) { props.heightDp.dp.roundToPx() }

    // One line, so one subject. A failure outranks live work -- it is the only
    // record the user gets -- and otherwise the newest job narrates.
    val subject = remember(activities) {
        activities.lastOrNull { it.phase is ActivityPhase.Failed } ?: activities.lastOrNull()
    }
    // A pick survives a re-emission but not the job leaving: the stack is the
    // control, so choosing a face is how the user changes what is narrated. One
    // line stays one line -- the alternative, growing into a list of rows, is the
    // toast stack this surface replaced, moored to a different corner.
    var picked by remember { mutableStateOf<String?>(null) }
    val subjectKey = picked?.takeIf { key -> activities.any { it.key == key } }
    val shown = subjectKey?.let { key -> activities.first { it.key == key } } ?: subject

    // The object must not outgrow the column it floats in: a long pack name on a
    // narrow window would otherwise push it past the edge, since the pill sizes to
    // its content. The cap turns that into an ellipsis instead.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cap = maxWidth * MAX_WIDTH_SHARE
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement =
                if (props.anchor == PillAnchor.Center) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            AnimatedVisibility(
                visible = subject != null,
                // Stage one: the object arrives as a ball, rising into place with
                // an overshoot. It is not yet a panel and does not pretend to be
                // one -- there is nothing to read until it has drawn itself out.
                enter = slideInVertically(
                    animationSpec = tween(style.animationDurationMs(260), easing = ArriveEasing),
                    initialOffsetY = { it * 2 },
                ) + scaleIn(
                    animationSpec = tween(style.animationDurationMs(260), easing = ArriveEasing),
                    initialScale = 0.5f,
                ) + fadeIn(tween(style.animationDurationMs(160))),
                exit = scaleOut(
                    animationSpec = tween(style.animationDurationMs(200)),
                    targetScale = 0.96f,
                ) + fadeOut(tween(style.animationDurationMs(160))),
            ) {
                if (subject != null) {
                    // Stage two: having landed, it opens. Width comes from the
                    // content and the corner travels from a circle to the panel's
                    // own radius, so one object becomes the other rather than a
                    // ball being swapped for a bar.
                    var open by remember(subject.key) { mutableStateOf(false) }
                    LaunchedEffect(subject.key) {
                        delay(style.animationDurationMs(220).toLong())
                        open = true
                    }
                    Pill(shown ?: subject, activities, { picked = it.key }, props, commands, s, cap, open)
                }
            }
        }
    }
}

/** Internal so a render sheet can exercise the presentation without Koin. */
@Composable
internal fun Pill(
    activity: Activity,
    all: List<Activity> = listOf(activity),
    onPick: (Activity) -> Unit = {},
    props: PillProps,
    commands: ActivityCommands?,
    s: AppStrings,
    maxWidth: Dp,
    open: Boolean = true,
) {
    val style = LocalStyle.current
    val colors = NxTheme.colors
    val height = props.heightDp.dp
    // A panel's corner, not a capsule's. A fully rounded object at this size
    // reads as a chip no matter what is in it; the radius is what makes it a
    // container. It comes from the panel token so the form axis still decides --
    // Brut takes it to near-square without a switch of its own.
    //
    // While the object is opening it travels from a circle to that radius, which
    // is the second half of the arrival: one shape becoming another.
    val corner by animateDpAsState(
        targetValue = if (open) style.panelCorner else height / 2,
        animationSpec = tween(style.animationDurationMs(380), easing = OpenEasing),
        label = "pillCorner",
    )
    val shape = RoundedCornerShape(corner)
    val fraction = activity.fraction()
    val failed = activity.phase as? ActivityPhase.Failed
    val accent = if (failed != null) colors.criticalAccent else colors.progressAccent

    NxSurface(
        level = NxSurfaceLevel.Floating,
        // The bound is required, not optional: the title takes a weight, and a
        // weight in a Row with unbounded width is undefined -- which is how the
        // controls ended up drawn outside the body.
        modifier = Modifier
            // Height is a floor, not a fixture: the object is as tall as what it
            // holds plus its padding, which is what stops it reading as something
            // squeezed into a strip.
            .heightIn(min = height)
            // Width has a floor too. Without one a short pack name collapses the
            // object into a chip and it stops reading as a place where the
            // launcher reports things.
            .widthIn(min = minOf(MIN_WIDTH, maxWidth), max = maxWidth)
            .animateContentSize(tween(style.animationDurationMs(380), easing = OpenEasing))
            .clip(shape),
        shape = shape,
        tier = props.frostTier,
        // Opaque body: the object floats over arbitrary content, so the
        // legibility floor cannot depend on what happens to be behind it.
        opaque = true,
    ) {
        // Rule 5: the measure is a property of the object, not a widget parked
        // inside it. Both overlays measure against the surface's OWN bounds via
        // this BoxScope -- matching a wrapping Box put the stroke outside the
        // body, which the render probe caught.
        if (props.progress == PillProgress.Fill) {
            Box(
                Modifier.matchParentSize().clip(shape),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxSize(fraction ?: 0f).background(accent.copy(alpha = 0.22f)))
            }
        }
        Row(
            modifier = Modifier
                .padding(start = if (props.collapsed) 11.dp else 10.dp, end = 14.dp)
                .height(height),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            SubjectStack(activity, all, fraction, accent, props.progress, onPick)
            // Collapsed keeps the ball and nothing else: the object is still
            // present and still measures, it just stops narrating. The same
            // shape the unfurl starts from, so there is one drawing rather
            // than a separate compact variant.
            if (props.collapsed || !open) return@Row
            // One weighted lane holds the name and the measure and absorbs all the
            // free width, which is what pushes the controls to the far edge. Two
            // competing weights split the row instead, and the name elided with
            // half the object standing empty beside it.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = activity.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The name yields first: the measure is the part a truncation
                    // would make useless.
                    modifier = Modifier.weight(1f, fill = false),
                )
                activity.measure(s)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (props.progress == PillProgress.Bar) {
                Box(Modifier.width(120.dp)) {
                    NxProgressBar(progress = fraction, color = accent)
                }
            }
            // The break between what this is about and what can be done to it.
            // Without it the row reads as one undifferentiated strip.
            if (props.showActions && activity.actions.isNotEmpty()) {
                VerticalDivider(Modifier.height(26.dp), color = colors.outline)
                Spacer(Modifier.width(2.dp))
            }
            if (props.showActions) {
                activity.actions.forEach { action ->
                    NxButton(
                        label = action.label(s),
                        onClick = { commands?.perform(activity, action) },
                        style = NxButtonStyle.Tertiary,
                        icon = action.icon(),
                        compact = true,
                    )
                }
            }
        }
        // Drawn last so it sits over the body's own bevel.
        if (props.progress == PillProgress.Edge) {
            EdgeMeasure(fraction, accent, shape, Modifier.matchParentSize())
        }
    }
}

/**
 * The perimeter as the track. The bevel already runs the whole way round every
 * surface, so a measure drawn along it needs no element of its own -- which also
 * means no stop indicator to explain and nothing that stays rounded when the
 * style squares.
 */
@Composable
private fun EdgeMeasure(
    fraction: Float?,
    color: Color,
    shape: androidx.compose.foundation.shape.RoundedCornerShape,
    modifier: Modifier,
) {
    val trackColor = NxTheme.colors.textSecondary.copy(alpha = 0.22f)
    val style = LocalStyle.current
    // A job whose size is not known yet still has to look alive. A static track
    // reads as stalled, which is what a launcher does for the first seconds of
    // every install -- exactly when the user is watching hardest.
    val sweep = if (fraction == null && style.animationMultiplier > 0f) {
        rememberInfiniteTransition(label = "pillSweep").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(style.animationDurationMs(1_600), easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "pillSweepValue",
        ).value
    } else {
        null
    }
    Canvas(modifier) {
        // Density-derived, not a raw pixel count: a 2px stroke is a hairline on a
        // 2x display and a heavy band on a 1x one. The render probe found this by
        // failing to see the measure at all at 2x.
        val strokeWidth = MEASURE_STROKE.toPx()
        val radius = shape.topStart.toPx(size, this)
        val outline = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(Offset.Zero, size).deflate(strokeWidth / 2f),
                    radiusX = radius,
                    radiusY = radius,
                ),
            )
        }
        drawPath(outline, trackColor, style = Stroke(width = strokeWidth))
        val measure = PathMeasure().apply { setPath(outline, false) }
        val total = measure.length
        val arc = Path()

        when {
            // Unknown size: a short arc travelling the perimeter.
            fraction == null && sweep != null -> {
                val span = total * INDETERMINATE_SPAN
                val head = total * sweep
                measure.getSegment(head, (head + span).coerceAtMost(total), arc, true)
                // Wrap the tail back to the start so the arc never vanishes at the seam.
                if (head + span > total) {
                    measure.getSegment(0f, head + span - total, arc, true)
                }
            }
            // Unknown size with motion off: a still, dimmed full perimeter. Busy,
            // not a percentage.
            fraction == null -> {
                drawPath(outline, color.copy(alpha = 0.35f), style = Stroke(width = strokeWidth))
                return@Canvas
            }
            fraction <= 0f -> return@Canvas
            else -> measure.getSegment(0f, total * fraction.coerceIn(0f, 1f), arc, true)
        }
        drawPath(arc, color, style = Stroke(width = strokeWidth))
    }
}

/**
 * The subject zone. One face per activity, overlapping, newest in front, because
 * a single icon in front of a list of four says the launcher is doing one thing.
 * Past [STACK_MAX] the count in the row carries the remainder rather than the
 * stack growing into an unreadable smear.
 */
@Composable
private fun SubjectStack(
    lead: Activity,
    all: List<Activity>,
    fraction: Float?,
    accent: Color,
    mode: PillProgress,
    onPick: (Activity) -> Unit,
) {
    // Lead in front: it is the one the row is describing.
    val shown = (listOf(lead) + all.filter { it.key != lead.key }).take(STACK_MAX)
    Box(contentAlignment = Alignment.Center) {
        // Reversed order with a reversed arrangement: the lead is drawn last, so
        // it is on top, and lands leftmost. The overflow tile is the tail --
        // furthest right, furthest back -- one more of the same object rather
        // than a note after the title.
        val hidden = all.size - shown.size
        Row(horizontalArrangement = Arrangement.spacedBy(-STACK_OVERLAP)) {
            shown.reversed().forEach { face -> Face(face) { onPick(face) } }
            if (hidden > 0) OverflowFace(hidden)
        }
        if (mode == PillProgress.Ring) {
            EdgeMeasure(fraction, accent, RoundedCornerShape(50), Modifier.size(36.dp))
        }
    }
}

/** The rest of the stack, as a face of its own. */
@Composable
private fun OverflowFace(count: Int) {
    val shape = RoundedCornerShape(faceCorner())
    Box(
        Modifier.size(FACE_SIZE).clip(shape)
            .background(NxTheme.colors.surfaceContainer)
            .border(1.5.dp, NxTheme.colors.surfaceContainerHigh, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "+$count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = NxTheme.colors.textSecondary,
        )
    }
}

/** Face radius, capped so it never becomes a disc but still squares under Brut. */
@Composable
private fun faceCorner(): Dp = minOf(LocalStyle.current.panelCorner, 9.dp)

@Composable
private fun Face(activity: Activity, onClick: () -> Unit) {
    val tint = NxTheme.colors.decorativeColor(activity.key)
    val initials = activity.title.take(2).uppercase()
    // Rounded squares. Circles at this size and overlap read as one smear; a
    // square corner keeps each face a separate object, and the radius still
    // follows the form axis.
    val shape = RoundedCornerShape(faceCorner())
    // The ring is the body colour, so the faces read as separate discs rather
    // than one blob when they overlap.
    val ring = NxTheme.colors.surfaceContainerHigh
    SubcomposeAsyncImage(
        model = activity.iconUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(FACE_SIZE).clip(shape).border(1.5.dp, ring, shape).clickable(onClick = onClick),
        loading = { Box(Modifier.fillMaxSize().background(tint)) },
        error = {
            Box(Modifier.fillMaxSize().background(tint), contentAlignment = Alignment.Center) {
                Text(
                    initials,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    )
}

/** Null when the job's size is unknown, which the measure renders as busy. */
private fun Activity.fraction(): Float? {
    val running = phase as? ActivityPhase.Running ?: return 1f
    return if (running.total > 0) (running.done.toFloat() / running.total) else null
}

/**
 * The quantity, in one zone for every kind. A game has no fraction to show, so
 * it is narrated by how long it has been up; everything else counts its work.
 * "34 of 97" and "14:32" sharing a slot is what makes a download and a running
 * game read as one object rather than two designs.
 */
private fun Activity.measure(s: AppStrings): String? {
    val failed = phase as? ActivityPhase.Failed
    if (failed != null) return failed.reason
    val running = phase as? ActivityPhase.Running ?: return null
    // A job whose size is not known yet still has something to say -- the stage
    // it is in, or the file it is on. Leaving the zone empty is what made the
    // first seconds of an install read as a bare name and nothing else.
    if (running.total <= 0) return running.detail
    return s.activityPillMeasure(running.done, running.total)
}

private fun ActivityAction.icon(): IconKey = when (this) {
    ActivityAction.Cancel -> NxIcon.Close
    ActivityAction.Pause -> NxIcon.Pause
}

private fun ActivityAction.label(s: AppStrings): String = when (this) {
    ActivityAction.Cancel -> s.activityPillCancel
    ActivityAction.Pause -> s.activityPillPause
}

/** Weight of the perimeter measure. Reads at any density; see [EdgeMeasure]. */
private val MEASURE_STROKE = 2.dp

/** Share of the content column the pill may occupy before the title elides. */
private const val MAX_WIDTH_SHARE = 0.72f

/** Share of the perimeter the indeterminate arc covers. */
private const val INDETERMINATE_SPAN = 0.22f

/** Faces the stack shows before the row's count takes over. */
private const val STACK_MAX = 3

/** How far each face hides behind the one in front of it. */
private val STACK_OVERLAP = 8.dp

/** Diameter of one face in the subject stack. */
private val FACE_SIZE = 32.dp

/** Floor on the object's width, so it reads as a panel rather than a chip. */
private val MIN_WIDTH = 380.dp

/** Arrival: overshoots, the way something landing does. */
private val ArriveEasing = CubicBezierEasing(0.15f, 1.4f, 0.64f, 0.96f)

/** Opening: fast then settling, with no overshoot to fight the arrival's. */
private val OpenEasing = CubicBezierEasing(0.16f, 0.84f, 0.28f, 1f)
