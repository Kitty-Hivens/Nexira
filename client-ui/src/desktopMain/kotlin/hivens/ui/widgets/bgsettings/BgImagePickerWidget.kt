package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.launch

@Widget(id = "bg.image.picker", displayName = "widget.bg.image.picker")
@Composable
fun BgImagePickerWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings
    val scope = rememberCoroutineScope()

    Column {
        SectionTitle(s.backgroundSectionImage)
        Spacer(Modifier.size(8.dp))
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick  = {
                    scope.launch {
                        FileKit.openFilePicker(
                            type           = FileKitType.File(extensions = listOf(
                                "png", "jpg", "jpeg", "webp", "bmp", "gif", "apng",
                                "mp4", "m4v", "mov", "webm", "mkv", "ogv",
                            )),
                            dialogSettings = FileKitDialogSettings(title = s.backgroundPickFile),
                        )?.path?.let { path ->
                            ctx.update { copy(imagePath = path, enabled = true) }
                        }
                    }
                },
                shape    = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            ) {
                Symbol(NxIcon.Image, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(s.backgroundPickButton)
            }
            if (settings.imagePath != null) {
                IconButton(onClick = { ctx.update { copy(imagePath = null, enabled = false) } }) {
                    Symbol(NxIcon.Delete, null, tint = NxTheme.colors.error)
                }
            }
        }
        if (settings.imagePath != null) {
            Spacer(Modifier.size(4.dp))
            Text(
                text  = settings.imagePath!!.substringAfterLast("/").substringAfterLast("\\"),
                style = MaterialTheme.typography.labelSmall,
                color = NxTheme.colors.textSecondary.copy(alpha = 0.5f),
            )
        }
    }
}
