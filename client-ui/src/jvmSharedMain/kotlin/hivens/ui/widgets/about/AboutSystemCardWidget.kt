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
import hivens.ui.widgets.Sources
import hivens.widget.api.rememberProps
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
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
//
// The display and the renderer are asked here rather than read from a source:
// both are about the window the card is drawn in, and on a phone the answer
// comes from the composition's own context.
@Widget(
    id = "about.system.card",
    displayName = "widget.about.system.card",
    propsClass = AboutSystemProps::class,
    surface = """{"fill":"card","border":{"widthDp":1.0}}""",
    needs = ["system.info"],
)
@Composable
fun AboutSystemCardWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<AboutSystemProps>()
    val facts = rememberScopedSource(Sources.SystemInfo).valueOrNull() ?: return
    val displayInfo = rememberDisplayInfo()
    val renderer = rememberRendererInfo()
    val s = LocalStrings.current

    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        SectionLabel(p.title.ifBlank { s.aboutSectionSystem })
        Spacer(Modifier.height(12.dp))

        val host = HostSystem.info

        val c        = facts.cpu
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
            append(if (facts.ramMb > 0) "${facts.ramMb} MB" else "Unknown")
            facts.swapMb?.takeIf { it > 0 }?.let { append(" · swap $it MB") }
        }

        InfoRow(NxIcon.Computer, s.aboutOs, host.os)
        InfoRow(NxIcon.Memory,   "CPU",     cpuValue)
        InfoRow(NxIcon.Storage,  "RAM",     ramValue)
        InfoRow(NxIcon.Code,     "Java",    "${host.javaVersion} (${host.javaVendor})")
        InfoRow(NxIcon.Tv,       "Display", displayInfo)
        InfoRow(NxIcon.Layers,   s.aboutRenderer, renderer)
    }
}
