package hivens.ui.widgets.sample

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.theme.CelestiaTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Analog clock with smooth-sweeping seconds. Theme-aware: dial reads
// surface, hands read textPrimary, accent ring reads primary. Per-
// second tick recomposes only the Canvas + the digital time line; the
// surrounding card stays still.
@Widget(id = "home.new.clock", displayName = "Clock")
@Composable
fun ClockWidget(instance: WidgetInstance) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            // 1Hz tick is enough; the second hand interpolates smoothly
            // in the canvas using milliseconds-of-second so we do not
            // need 60Hz recomposition.
            delay(500L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(glassSurfaceAlpha(0.45f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text       = "Часы",
            style      = MaterialTheme.typography.labelLarge,
            color      = CelestiaTheme.colors.textSecondary,
            fontWeight = FontWeight.Medium,
            modifier   = Modifier.align(Alignment.Start),
        )
        Spacer(Modifier.height(10.dp))

        ClockFace(
            time     = now,
            modifier = Modifier.size(140.dp),
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text       = TIME_FORMATTER.format(now),
            style      = MaterialTheme.typography.titleLarge,
            color      = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.Light,
        )
        Text(
            text  = DATE_FORMATTER.format(now),
            style = MaterialTheme.typography.bodySmall,
            color = CelestiaTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun ClockFace(time: LocalDateTime, modifier: Modifier = Modifier) {
    val dialColor   = CelestiaTheme.colors.surface
    val rimColor    = CelestiaTheme.colors.outline.copy(alpha = 0.50f)
    val markerColor = CelestiaTheme.colors.textSecondary.copy(alpha = 0.75f)
    val hourColor   = CelestiaTheme.colors.textPrimary
    val minuteColor = CelestiaTheme.colors.textPrimary
    val secondColor = CelestiaTheme.colors.primary

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(dialColor, dialColor.copy(alpha = 0.85f)),
                ),
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().padding(0.dp).align(Alignment.Center).size(140.dp)) {
            val radius = min(size.width, size.height) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // Rim
            drawCircle(color = rimColor, radius = radius - 1f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))

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
            // Second hand (counter-weighted)
            rotate(degrees = second * 6f - 90f, pivot = center) {
                drawLine(
                    color       = secondColor,
                    start       = Offset(center.x - radius * 0.12f, center.y),
                    end         = Offset(center.x + radius * 0.82f, center.y),
                    strokeWidth = 1.5f,
                    cap         = StrokeCap.Round,
                )
            }

            // Hub
            drawCircle(color = secondColor, radius = 4.5f, center = center)
            drawCircle(color = dialColor,   radius = 2.0f, center = center)
        }
    }
}

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")
