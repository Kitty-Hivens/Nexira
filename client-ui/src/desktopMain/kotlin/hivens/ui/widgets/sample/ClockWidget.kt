package hivens.ui.widgets.sample

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalStyle
import hivens.ui.widgets.AdaptiveWidget
import hivens.ui.widgets.scaled
import hivens.ui.widgets.toWidgetColorOrNull
import hivens.widget.api.rememberProps
import hivens.widget.model.PropColor
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Serializable
enum class ClockMode { Analog, Digital, Both }

@Serializable
data class ClockProps(
    @PropLabel("widget.home.new.clock.mode") val mode: ClockMode = ClockMode.Both,
    @PropLabel("widget.home.new.clock.format24h") val format24h: Boolean = true,
    @PropLabel("widget.home.new.clock.showSeconds") val showSeconds: Boolean = true,
    @PropLabel("widget.home.new.clock.title") val title: String = "",
    @PropLabel("widget.home.new.clock.faceSize") @PropRange(80.0, 200.0) val faceSize: Int = 140,
    @PropLabel("widget.home.new.clock.accent") @PropColor val accent: String = "",
)

// Analog + digital clock with a once-per-second second hand. Theme-aware: dial
// reads surface, hands read textPrimary, accent (second hand / hub) reads
// the accent prop or falls back to primary. Per-second tick recomposes
// only the Canvas + the digital time line; the surrounding card stays
// still.
@Widget(id = "home.new.clock", displayName = "widget.home.new.clock", propsClass = ClockProps::class)
@Composable
fun ClockWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<ClockProps>()
    val accent = p.accent.toWidgetColorOrNull()
    // Formatted in the launcher's language, not the machine's: the weekday and
    // month names below sit inside a Russian shell on an English Windows unless
    // the locale is passed, and the AM/PM marker has the same problem.
    val locale = LocalStrings.current.locale
    val timeFormatter = remember(p.format24h, p.showSeconds, locale) {
        DateTimeFormatter.ofPattern(
            buildString {
                append(if (p.format24h) "HH:mm" else "hh:mm")
                if (p.showSeconds) append(":ss")
                if (!p.format24h) append(" a")
            },
            locale,
        )
    }
    val dateFormatter = remember(locale) { DateTimeFormatter.ofPattern("EEE, d MMM", locale) }

    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            // Tick once per second, aligned to the next second boundary so the
            // hand steps cleanly on the second (quartz-style) rather than
            // drifting or double-stepping. A continuous sweep would need
            // per-frame recomposition, which a background clock does not earn.
            delay(1000L - now.nano / 1_000_000L)
        }
    }

    AdaptiveWidget(referenceWidth = 200.dp, referenceHeight = 230.dp) { scale ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 12.dp * scale)
                .clip(RoundedCornerShape(LocalStyle.current.cardCorner * scale))
                .background(glassSurfaceAlpha(0.65f))
                .padding(horizontal = 16.dp * scale, vertical = 14.dp * scale),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (p.title.isNotBlank()) {
                Text(
                    text       = p.title,
                    style      = MaterialTheme.typography.labelLarge.scaled(scale),
                    color      = NxTheme.colors.textSecondary,
                    fontWeight = FontWeight.Medium,
                    modifier   = Modifier.align(Alignment.Start),
                )
                Spacer(Modifier.height(10.dp * scale))
            }

            if (p.mode != ClockMode.Digital) {
                ClockFace(
                    time           = now,
                    showSeconds    = p.showSeconds,
                    accentOverride = accent,
                    modifier       = Modifier.size(p.faceSize.dp * scale),
                )
                Spacer(Modifier.height(10.dp * scale))
            }

            if (p.mode != ClockMode.Analog) {
                Text(
                    text       = timeFormatter.format(now),
                    style      = MaterialTheme.typography.titleLarge.scaled(scale),
                    color      = NxTheme.colors.textPrimary,
                    fontWeight = FontWeight.Light,
                )
                Text(
                    text  = dateFormatter.format(now),
                    style = MaterialTheme.typography.bodySmall.scaled(scale),
                    color = NxTheme.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun ClockFace(
    time: LocalDateTime,
    showSeconds: Boolean,
    accentOverride: Color?,
    modifier: Modifier = Modifier,
) {
    val dialColor   = NxTheme.colors.surface
    val rimColor    = NxTheme.colors.outline.copy(alpha = 0.50f)
    val markerColor = NxTheme.colors.textSecondary.copy(alpha = 0.75f)
    val hourColor   = NxTheme.colors.textPrimary
    val minuteColor = NxTheme.colors.textPrimary
    val secondColor = accentOverride ?: NxTheme.colors.primary

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(dialColor, dialColor.copy(alpha = 0.85f)),
                ),
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = min(size.width, size.height) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // Rim
            drawCircle(color = rimColor, radius = radius - 1f, center = center, style = Stroke(width = 2f))

            // Hour markers: long at 12/3/6/9, short elsewhere
            for (i in 0 until 12) {
                val angle = i * 30.0
                val isMajor = i % 3 == 0
                val outer = radius - 6f
                val inner = if (isMajor) radius - 18f else radius - 12f
                val rad = Math.toRadians(angle - 90)
                val sx = center.x + cos(rad).toFloat() * outer
                val sy = center.y + sin(rad).toFloat() * outer
                val ex = center.x + cos(rad).toFloat() * inner
                val ey = center.y + sin(rad).toFloat() * inner
                drawLine(
                    color       = markerColor,
                    start       = Offset(sx, sy),
                    end         = Offset(ex, ey),
                    strokeWidth = if (isMajor) 2.5f else 1.5f,
                    cap         = StrokeCap.Round,
                )
            }

            val hour   = (time.hour % 12) + time.minute / 60f
            val minute = time.minute + time.second / 60f
            val second = time.second + time.nano / 1_000_000_000f

            // Hour hand
            rotate(degrees = hour * 30f - 90f, pivot = center) {
                drawLine(
                    color       = hourColor,
                    start       = center,
                    end         = Offset(center.x + radius * 0.50f, center.y),
                    strokeWidth = 5f,
                    cap         = StrokeCap.Round,
                )
            }
            // Minute hand
            rotate(degrees = minute * 6f - 90f, pivot = center) {
                drawLine(
                    color       = minuteColor,
                    start       = center,
                    end         = Offset(center.x + radius * 0.72f, center.y),
                    strokeWidth = 3.5f,
                    cap         = StrokeCap.Round,
                )
            }
            // Second hand (counter-weighted) -- only when the showSeconds
            // prop is on.
            if (showSeconds) {
                rotate(degrees = second * 6f - 90f, pivot = center) {
                    drawLine(
                        color       = secondColor,
                        start       = Offset(center.x - radius * 0.12f, center.y),
                        end         = Offset(center.x + radius * 0.82f, center.y),
                        strokeWidth = 1.5f,
                        cap         = StrokeCap.Round,
                    )
                }
            }

            // Hub
            drawCircle(color = secondColor, radius = 4.5f, center = center)
            drawCircle(color = dialColor,   radius = 2.0f, center = center)
        }
    }
}
