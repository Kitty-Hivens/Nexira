package hivens.ui.widgets.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.NxTheme
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class LibraryHeaderProps(
    @PropLabel("widget.library.header.title") val title: String = "",
    @PropLabel("widget.library.header.subtitle") val subtitle: String = "",
    // Hidden by default: the top-bar breadcrumb already names the location, so the
    // in-screen title/subtitle is a duplicate. Flip on per-layout to bring it back.
    @PropLabel("widget.library.header.show") val show: Boolean = false,
)

@Widget(id = "library.header", displayName = "widget.library.header", propsClass = LibraryHeaderProps::class)
@Composable
fun LibraryHeader(instance: WidgetInstance) {
    val p = instance.rememberProps<LibraryHeaderProps>()
    if (!p.show) return
    val s = LocalStrings.current
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            text       = p.title.ifBlank { s.libraryHeaderTitle },
            style      = MaterialTheme.typography.headlineSmall,
            color      = NxTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text  = p.subtitle.ifBlank { s.libraryHeaderSubtitle },
            style = MaterialTheme.typography.bodyMedium,
            color = NxTheme.colors.textSecondary,
        )
    }
}
