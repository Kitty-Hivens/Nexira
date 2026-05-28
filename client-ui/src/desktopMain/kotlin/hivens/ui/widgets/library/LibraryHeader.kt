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
import hivens.ui.theme.CelestiaTheme
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class LibraryHeaderProps(
    @PropLabel("Заголовок") val title: String = "БИБЛИОТЕКА",
    @PropLabel("Подзаголовок") val subtitle: String = "Установленные сборки",
)

@Widget(id = "library.header", displayName = "Library Header", propsClass = LibraryHeaderProps::class)
@Composable
fun LibraryHeader(instance: WidgetInstance) {
    val p = instance.rememberProps<LibraryHeaderProps>()
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            text       = p.title,
            style      = MaterialTheme.typography.headlineSmall,
            color      = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text  = p.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = CelestiaTheme.colors.textSecondary,
        )
    }
}
