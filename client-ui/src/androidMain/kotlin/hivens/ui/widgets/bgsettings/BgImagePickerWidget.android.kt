package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.background.BackgroundManager
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxIconButton
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import hivens.ui.utils.pickFile
import hivens.ui.utils.rememberFileDialogSettings
import hivens.widget.model.Widget
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.extension
import io.github.vinceglb.filekit.source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.asInputStream
import kotlinx.io.buffered
import org.koin.compose.koinInject
import java.io.File
import java.nio.file.Path

/**
 * Picks the wallpaper through the system picker and keeps a copy of it in the app's own storage:
 * the picker hands over a handle that is only good for the moment, and the background is read
 * again on every launch.
 */
@Widget(id = "bg.image.picker", displayName = "widget.bg.image.picker", needs = ["background.settings", "background.update"])
@Composable
fun BgImagePickerWidget() {
    val bg = rememberBgSettings() ?: return
    val s = LocalStrings.current
    val settings = bg.value
    val scope = rememberCoroutineScope()
    val appScope = koinInject<CoroutineScope>()
    val backgrounds = koinInject<BackgroundManager>()
    val dataDir = koinInject<Path>()
    val dialogSettings = rememberFileDialogSettings(s.backgroundPickFile)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(s.backgroundSectionImage)
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NxButton(
                label    = s.backgroundPickButton,
                icon     = NxIcon.Image,
                style    = NxButtonStyle.Secondary,
                modifier = Modifier.weight(1f),
                onClick  = {
                    scope.launch {
                        val picked = pickFile(type = FileKitType.ImageAndVideo, settings = dialogSettings) ?: return@launch
                        val extension = picked.extension.ifBlank { "img" }
                        // On the app's scope: leaving the screen must not drop a copy halfway.
                        appScope.launch {
                            val stored = withContext(Dispatchers.IO) {
                                val dir = dataDir.resolve("background-cache").toFile().also { it.mkdirs() }
                                File(dir, "wallpaper-${System.nanoTime()}.$extension").also { target ->
                                    picked.source().buffered().asInputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                                }
                            }
                            backgrounds.update { it.copy(imagePath = stored.absolutePath, enabled = true) }
                            withContext(Dispatchers.IO) {
                                stored.parentFile?.listFiles { f -> f.name.startsWith("wallpaper-") && f != stored }
                                    ?.forEach { it.delete() }
                            }
                        }
                    }
                },
            )
            if (settings.imagePath != null) {
                NxIconButton(
                    NxIcon.Delete, null,
                    onClick = { bg.update { copy(imagePath = null, enabled = false) } },
                    tint    = NxColor.status(Status.Error),
                )
            }
        }
        settings.imagePath?.let {
            Text(
                text  = it.substringAfterLast("/"),
                style = MaterialTheme.typography.labelSmall,
                color = NxInk.quiet.copy(alpha = 0.5f),
            )
        }
    }
}
