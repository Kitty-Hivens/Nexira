package hivens.ui.widgets.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.components.GlassCard
import hivens.ui.i18n.LocalStrings
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
@Widget(id = "about.system.card", displayName = "widget.about.system.card", propsClass = AboutSystemProps::class)
@Composable
fun AboutSystemCardWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<AboutSystemProps>()
    val ctx = LocalAboutContext.current
    val s = LocalStrings.current

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            SectionLabel(p.title.ifBlank { s.aboutSectionSystem })
            Spacer(Modifier.height(12.dp))

            val osName     = System.getProperty("os.name")
            val osArch     = System.getProperty("os.arch")
            val osVer      = System.getProperty("os.version")
            val javaVer    = System.getProperty("java.version")
            val javaVendor = System.getProperty("java.vendor")
            val cores      = Runtime.getRuntime().availableProcessors()
            val maxHeap    = Runtime.getRuntime().maxMemory() / (1024 * 1024)

            InfoRow(Icons.Default.Computer, s.aboutOs, "$osName $osVer ($osArch)")
            InfoRow(Icons.Default.Memory,   "CPU",     "$cores threads")
            InfoRow(
                icon  = Icons.Default.Storage,
                label = "RAM",
                value = "${if (ctx.systemRam > 0) "${ctx.systemRam} MB" else "Unknown"} (Heap: $maxHeap MB)",
            )
            InfoRow(Icons.Default.Code, "Java",    "$javaVer ($javaVendor)")
            InfoRow(Icons.Default.Tv,   "Display", ctx.displayRes)
        }
    }
}
