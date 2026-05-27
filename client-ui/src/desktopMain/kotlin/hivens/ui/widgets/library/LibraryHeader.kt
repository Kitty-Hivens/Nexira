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
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "library.header", displayName = "Library Header")
@Composable
fun LibraryHeader(instance: WidgetInstance) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            text       = "БИБЛИОТЕКА",
            style      = MaterialTheme.typography.headlineSmall,
            color      = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text  = "Установленные сборки",
            style = MaterialTheme.typography.bodyMedium,
            color = CelestiaTheme.colors.textSecondary,
        )
    }
}
