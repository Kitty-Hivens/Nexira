package hivens.ui.activity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import hivens.core.activity.Activity
import hivens.core.activity.ActivityAction
import hivens.core.activity.ActivityKind
import hivens.core.activity.ActivityPhase
import hivens.core.activity.ActivityRegistry
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxIconButton
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
    @PropLabel("widget.activity.pill.anchor") val anchor: PillAnchor = PillAnchor.Left,
    // Flat rather than Clear: Clear is a bodiless glass coat, which reads over a
    // wallpaper and disappears over the near-black default ground.
    @PropLabel("widget.appshell.region.frostTier") val frostTier: FrostTier = FrostTier.Heavy,
    @PropLabel("widget.activity.pill.heightDp") @PropRange(22.0, 56.0) val heightDp: Int = 44,
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

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement =
                if (props.anchor == PillAnchor.Center) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            AnimatedVisibility(
                visible = subject != null,
                // Born from the ball: the initial width is the pill's own height,
                // which at a 50% corner is a circle. Start-anchored, so the
                // subject icon does not move while the body unrolls past it.
                enter = expandHorizontally(
                    animationSpec = tween(style.animationDurationMs(420)),
                    expandFrom = Alignment.Start,
                    initialWidth = { ballPx },
                ) + fadeIn(tween(style.animationDurationMs(160))),
                exit = shrinkHorizontally(
                    animationSpec = tween(style.animationDurationMs(260)),
                    shrinkTowards = Alignment.Start,
                    targetWidth = { ballPx },
                ) + fadeOut(tween(style.animationDurationMs(140))),
            ) {
                subject?.let { Pill(it, props, commands, s) }
            }
        }
    }
}

@Composable
private fun Pill(
    activity: Activity,
    props: PillProps,
    commands: ActivityCommands,
    s: AppStrings,
) {
    val style = LocalStyle.current
    val colors = NxTheme.colors
    val height = props.heightDp.dp
    // A pill and a circle are the same shape; Brut squares both through the
    // badge spec, the same place every other small shell reads its corner.
    val shape = style.badgeStyle.shape()
    val fraction = activity.fraction()
    val failed = activity.phase as? ActivityPhase.Failed
    val accent = if (failed != null) colors.criticalAccent else colors.progressAccent

    Box {
        NxSurface(
            level = NxSurfaceLevel.Floating,
            modifier = Modifier.height(height).clip(shape),
            shape = shape,
            tier = props.frostTier,
            // Opaque body: the object floats over arbitrary content, so the
            // legibility floor cannot depend on what happens to be behind it.
            opaque = true,
        ) {
            Row(
                modifier = Modifier.padding(start = 7.dp, end = 8.dp).height(height),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Subject(activity, fraction, accent, props.progress)
                Text(
                    text = activity.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                activity.measure(s)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                }
                if (props.progress == PillProgress.Bar) {
                    Box(Modifier.width(120.dp)) {
                        NxProgressBar(progress = fraction, color = accent)
                    }
                }
                Spacer(Modifier.width(2.dp))
                if (props.showActions) {
                    activity.actions.forEach { action ->
                        NxIconButton(
                            icon = action.icon(),
                            contentDescription = action.label(s),
                            onClick = { commands.perform(activity, action) },
                        )
                    }
                }
            }
        }

        // Rule 5: the measure is a property of the object, not a widget parked
        // inside it. Drawn last so it sits over the body's own edge.
        if (props.progress == PillProgress.Edge) {
            EdgeMeasure(fraction, accent, shape, Modifier.matchParentSize())
        }
        if (props.progress == PillProgress.Fill) {
            Box(
                Modifier.matchParentSize().clip(shape),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .fillMaxSize(fraction ?: 0f)
                        .background(accent.copy(alpha = 0.22f)),
                )
            }
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
    Canvas(modifier) {
        val radius = shape.topStart.toPx(size, this)
        val outline = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(Offset.Zero, size).deflate(1f),
                    radiusX = radius,
                    radiusY = radius,
                ),
            )
        }
        drawPath(outline, trackColor, style = Stroke(width = 2f))
        if (fraction == null || fraction <= 0f) return@Canvas

        val measure = PathMeasure().apply { setPath(outline, false) }
        val done = Path()
        measure.getSegment(0f, measure.length * fraction.coerceIn(0f, 1f), done, true)
        drawPath(done, color, style = Stroke(width = 2f))
    }
}

/** Leading icon; carries the measure itself under the Ring treatment. */
@Composable
private fun Subject(activity: Activity, fraction: Float?, accent: Color, mode: PillProgress) {
    val tint = NxTheme.colors.decorativeColor(activity.key)
    val initials = activity.title.take(2).uppercase()
    val shape = LocalStyle.current.badgeStyle.shape()

    Box(contentAlignment = Alignment.Center) {
        SubcomposeAsyncImage(
            model = activity.iconUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(28.dp).clip(shape),
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
    if (kind == ActivityKind.Game) return null // elapsed time is composed by the caller's clock
    val running = phase as? ActivityPhase.Running ?: return null
    if (running.total <= 0) return null
    return s.activityPillMeasure(running.done, running.total)
}

private fun ActivityAction.icon(): IconKey = when (this) {
    ActivityAction.Cancel -> NxIcon.Close
    ActivityAction.Stop -> NxIcon.Stop
    ActivityAction.Pause -> NxIcon.Pause
}

private fun ActivityAction.label(s: AppStrings): String = when (this) {
    ActivityAction.Cancel -> s.activityPillCancel
    ActivityAction.Stop -> s.activityPillStop
    ActivityAction.Pause -> s.activityPillPause
}
