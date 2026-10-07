package hivens.ui.widgets.home.new

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.NxInk
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class TimeProps(
    @PropLabel("widget.home.new.clock.format24h") val format24h: Boolean = true,
    @PropLabel("widget.home.new.time.showDate") val showDate: Boolean = true,
)

// The time, set as the largest thing on the surface, and the date under it.
//
// For an arrangement where Home is mostly the wallpaper and the time is what the
// eye lands on: a readout, not an instrument, so there is no dial and no seconds.
// It changes once a minute and wakes once a minute, on the minute. It sits on its
// own plane like every other piece of text on Home, because a wallpaper under it is
// whatever picture somebody chose.
@Widget(
    id = "home.new.time",
    enter = "fade",
    displayName = "widget.home.new.time",
    propsClass = TimeProps::class,
    surface = """{"fill":"panel"}""",
    minWidth = 200, minHeight = 100,
)
@Composable
fun HomeNewTime(instance: WidgetInstance) {
    val p = instance.rememberProps<TimeProps>()
    val s = LocalStrings.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val untilNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
            delay(untilNextMinute.milliseconds)
            now = LocalDateTime.now()
        }
    }
    val locale = s.locale
    val time = remember(now, p.format24h, locale) {
        now.format(DateTimeFormatter.ofPattern(if (p.format24h) "HH:mm" else "h:mm", locale))
    }
    val date = remember(now, locale) {
        now.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
    }
    Column(Modifier.padding(horizontal = 28.dp, vertical = 20.dp)) {
        Text(time, fontSize = 88.sp, lineHeight = 90.sp, fontWeight = FontWeight.Light, color = NxInk.main)
        if (p.showDate) {
            Text(date, style = MaterialTheme.typography.titleLarge, color = NxInk.quiet)
        }
    }
}
