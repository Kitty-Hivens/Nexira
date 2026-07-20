package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.background.BackgroundMediaKind
import hivens.ui.background.BackgroundOptimizer
import hivens.ui.background.backgroundMediaKind
import hivens.ui.background.physicalScreenHeight
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxIconButton
import hivens.ui.theme.NxTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import java.nio.file.Path

@Widget(id = "bg.image.picker", displayName = "widget.bg.image.picker")
@Composable
fun BgImagePickerWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings
    val scope = rememberCoroutineScope()
    val dataDir = koinInject<Path>()
    val appScope = koinInject<CoroutineScope>()
    val optimizer = remember(dataDir) { BackgroundOptimizer(dataDir.resolve("background-cache"), appScope) }
    // Downscale target: the monitor's physical pixel height, so a 4K source becomes a
    // display-res wallpaper once and stays crisp at any window size up to the screen.
    val targetHeight = remember { physicalScreenHeight() }
    var optimizing by remember { mutableStateOf(false) }

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
                enabled  = !optimizing,
                modifier = Modifier.weight(1f),
                onClick  = {
                    scope.launch {
                        val picked = FileKit.openFilePicker(
                            type           = FileKitType.File(extensions = listOf(
                                "png", "jpg", "jpeg", "webp", "bmp", "gif", "apng",
                                "mp4", "m4v", "mov", "webm", "mkv", "ogv",
                            )),
                            dialogSettings = FileKitDialogSettings(title = s.backgroundPickFile),
                        )?.path ?: return@launch
                        // Time-based media (video, GIF, animated PNG/WebP) taller than
                        // the screen is transcoded down once and cached; optimize()
                        // returns the source untouched in every other case. Stills fall
                        // through to the load-time image cache instead.
                        val timeBased = withContext(Dispatchers.IO) {
                            backgroundMediaKind(File(picked)) == BackgroundMediaKind.TimeBased
                        }
                        val resolved = if (timeBased && targetHeight > 0) {
                            optimizing = true
                            try {
                                optimizer.optimize(Path.of(picked), targetHeight).toString()
                            } catch (e: Exception) {
                                picked
                            } finally {
                                optimizing = false
                            }
                        } else {
                            picked
                        }
                        ctx.update { copy(imagePath = resolved, enabled = true) }
                    }
                },
            )
            if (optimizing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = NxTheme.colors.primary)
            } else if (settings.imagePath != null) {
                NxIconButton(
                    NxIcon.Delete, null,
                    onClick = { ctx.update { copy(imagePath = null, enabled = false) } },
                    tint    = NxTheme.colors.error,
                )
            }
        }
        if (settings.imagePath != null) {
            Text(
                text  = settings.imagePath!!.substringAfterLast("/").substringAfterLast("\\"),
                style = MaterialTheme.typography.labelSmall,
                color = NxTheme.colors.textSecondary.copy(alpha = 0.5f),
            )
        }
    }
}
