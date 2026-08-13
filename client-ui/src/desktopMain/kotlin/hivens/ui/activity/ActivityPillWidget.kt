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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.core.activity.Activity
import hivens.core.activity.ActivityAction
import hivens.core.activity.ActivityPhase
import hivens.core.activity.isTerminal
import hivens.core.activity.ActivityRegistry
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxProgressBar
import hivens.ui.surface.FrostTier
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.Motion
import hivens.ui.theme.NxTheme
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.milliseconds

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
    // Clear, which paired with the opaque body below is the plainest plane there
    // is: the tonal body, the hairline, the cast shadow, nothing else. The object
    // floats over arbitrary content so it cannot let anything read through, and
    // once it cannot, every coat above the body is either invisible or a second
    // fill shifting a tone the ladder already chose.
    @PropLabel("widget.appshell.region.frostTier") val frostTier: FrostTier = FrostTier.Clear,
    @PropLabel("widget.activity.pill.heightDp") @PropRange(40.0, 76.0) val heightDp: Int = 56,
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
    val selections: SelectionRegistry = koinInject()
    val selection by selections.selection.collectAsState()
    val activities by registry.activities.collectAsState()
    val style = LocalStyle.current
    val s = LocalStrings.current

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
                visible = subject != null || selection != null,
                // Stage one: the object arrives as a ball, rising into place with
                // an overshoot. It is not yet a panel and does not pretend to be
                // one -- there is nothing to read until it has drawn itself out.
                enter = slideInVertically(
                    animationSpec = Motion.emphasis.of(),
                    initialOffsetY = { it * 2 },
                ) + scaleIn(
                    animationSpec = Motion.emphasis.of(),
                    initialScale = 0.5f,
                ) + fadeIn(Motion.fade.of()),
                exit = scaleOut(
                    animationSpec = Motion.fade.of(),
                    targetScale = 0.96f,
                ) + fadeOut(Motion.fade.of()),
            ) {
                val hasBody = selection != null || subject != null
                if (hasBody) {
                    // Stage two: having landed, it opens. Width comes from the
                    // content and the corner travels from a circle to the panel's
                    // own radius, so one object becomes the other rather than a
                    // ball being swapped for a bar.
                    val birthKey = selection?.let { "selection" } ?: subject?.key
                    var open by remember(birthKey) { mutableStateOf(false) }
                    // Opens once the arrival has finished, so the two stages read
                    // as one object rather than overlapping. Read out here: a role
                    // needs composition, the effect body does not have it.
                    val arrivalMs = Motion.emphasis.durationMs.toLong()
                    LaunchedEffect(birthKey) {
                        delay(arrivalMs.milliseconds)
                        open = true
                    }
                    // Selection takes the body. What the launcher is doing on its
                    // own carries on underneath -- its lead face stays at the left
                    // as a token so nothing is lost, and clicking it drops the
                    // selection and hands the body back.
                    val sel = selection
                    if (sel != null) {
                        SelectionPill(sel, shown ?: subject, props, s, cap, open)
                    } else if (subject != null) {
                        Pill(shown ?: subject, activities, { picked = it.key }, props, commands, s, cap, open)
                    }
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
        animationSpec = Motion.reveal.of(),
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
            // A floor the content is meant to meet, not exceed. Set above what the
            // row needs and the surplus becomes slack the arrangement spreads
            // around, which reads as everything inside sitting a few pixels off
            // from where it belongs.
            .heightIn(min = height)
            // A ceiling only. A floor forces the object wider than what is in it
            // and the surplus becomes a gap down the middle, which reads as two
            // unrelated clusters rather than one object.
            .widthIn(max = maxWidth)
            .animateContentSize(Motion.reveal.of())
            .clip(shape),
        shape = shape,
        tier = props.frostTier,
        // It floats over the page, so it casts. Missing here while the selection
        // body set it meant the same object gained a shadow the moment a selection
        // took over and lost it again afterwards.
        elevated = true,
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
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = activity.title,
                    style = MaterialTheme.typography.titleMedium,
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
                // A settled entry has no work left to act on, so what it needs is
                // a way off the surface. A failure in particular never leaves by
                // age -- that is deliberate, and it is why this has to exist.
                val controls =
                    if (activity.phase.isTerminal) activity.actions + ActivityAction.Dismiss
                    else activity.actions
                controls.forEach { action ->
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
    val sweep = if (fraction == null && !Motion.isStill) {
        rememberInfiniteTransition(label = "pillSweep").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = Motion.sweep.of(),
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
            shown.reversed().forEach { face ->
                StackFace(face.key, face.title, face.iconUrl) { onPick(face) }
            }
            if (hidden > 0) StackOverflow(hidden)
        }
        if (mode == PillProgress.Ring) {
            EdgeMeasure(fraction, accent, RoundedCornerShape(50), Modifier.size(36.dp))
        }
    }
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
    ActivityAction.Dismiss -> NxIcon.Close
}

private fun ActivityAction.label(s: AppStrings): String = when (this) {
    ActivityAction.Cancel -> s.activityPillCancel
    ActivityAction.Pause -> s.activityPillPause
    ActivityAction.Dismiss -> s.activityPillDismiss
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
