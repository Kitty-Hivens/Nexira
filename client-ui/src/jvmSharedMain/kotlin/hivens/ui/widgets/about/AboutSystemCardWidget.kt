package hivens.ui.widgets.about

import hivens.core.jvm.HostSystem
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class AboutSystemProps(
    @PropLabel("widget.about.system.card.title") val title: String = "",
)

// OS / CPU / RAM / Java / Display info rows in one card.
// Per-row atomization would give the user editor freedom they would
// not actually exercise (no one hides "RAM" but keeps "CPU"), and
// would either explode into five mini-cards or leave bare rows
// floating with no visual grouping.
@Widget(
    id = "about.system.card",
    displayName = "widget.about.system.card",
    propsClass = AboutSystemProps::class,
    surface = """{"fill":"card","border":{"widthDp":1.0}}""",
)
@Composable
fun AboutSystemCardWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<AboutSystemProps>()
    val ctx = LocalAboutContext.current
    val s = LocalStrings.current

    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        SectionLabel(p.title.ifBlank { s.aboutSectionSystem })
        Spacer(Modifier.height(12.dp))

        val host = HostSystem.info

        val c        = ctx.cpu
        val physical = c.physicalCores
        val maxMhz   = c.maxMhz
        val minMhz   = c.minMhz
        val cpuValue = buildString {
            if (physical != null) append("$physical cores / ")
            append("${c.logicalThreads} threads")
            if (maxMhz != null) {
                val hi = "%.1f".format(maxMhz / 1000.0)
                val lo = minMhz?.let { "%.1f".format(it / 1000.0) }
                append(if (lo != null) " · $lo–$hi GHz" else " · $hi GHz")
            }
        }
        val ramValue = buildString {
            append(if (ctx.systemRam > 0) "${ctx.systemRam} MB" else "Unknown")
            ctx.swapMb?.takeIf { it > 0 }?.let { append(" · swap $it MB") }
        }

        InfoRow(NxIcon.Computer, s.aboutOs, host.os)
        InfoRow(NxIcon.Memory,   "CPU",     cpuValue)
        InfoRow(NxIcon.Storage,  "RAM",     ramValue)
        InfoRow(NxIcon.Code,     "Java",    "${host.javaVersion} (${host.javaVendor})")
        InfoRow(NxIcon.Tv,       "Display", ctx.displayInfo)
        InfoRow(NxIcon.Layers,   s.aboutRenderer, ctx.renderer)
    }
}
