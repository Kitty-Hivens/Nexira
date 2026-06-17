package hivens.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import hivens.launcher.PackImportService
import hivens.ui.AppState
import hivens.ui.Screen
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.CelestiaTheme
import hivens.ui.widgets.library.LibraryContext
import hivens.ui.widgets.library.LocalLibraryContext
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import java.nio.file.Path
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Library = user's collection of installed packs. Surface composable:
 * owns the screen padding + header/body column; widget content
 * (header text, list-or-empty) resolves through SlotRenderer against
 * the layout graph. A bottom-right action imports a pack from a local
 * `.mrpack`/`.zip` (Modrinth installs fully, CurseForge best-effort) and
 * opens the new instance -- it lives here because importing adds to the
 * collection, not to the Browse catalogue.
 */
@Composable
fun LibraryScreen(
    appState: AppState,
    onScreenChange: (Screen) -> Unit,
) {
    PuppetScreen("Library")

    val s = LocalStrings.current
    val importService: PackImportService = koinInject()
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    // Pick a .mrpack/.zip and import it, then open the new instance.
    fun startImport() {
        scope.launch {
            val picked = FileKit.openFilePicker(
                type = FileKitType.File(extensions = listOf("mrpack", "zip")),
                dialogSettings = FileKitDialogSettings(title = s.browseImport),
            )
            val path = picked?.path ?: return@launch
            importing = true
            importError = null
            try {
                onScreenChange(Screen.PackDetail(importService.import(Path.of(path)).id))
            } catch (e: Exception) {
                importError = e.message ?: s.browseDetailInstallFailedGeneric
            } finally {
                importing = false
            }
        }
    }

    val ctx = remember(appState, onScreenChange) {
        LibraryContext(appState = appState, onScreenChange = onScreenChange)
    }
    CompositionLocalProvider(LocalLibraryContext provides ctx) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
                SlotRenderer(
                    SurfaceId(SURFACE),
                    SlotId("header"),
                    modifier = Modifier.fillMaxWidth(),
                    spacing  = 8.dp,
                )
                // No verticalScroll on the body slot. Wrapping a Lazy
                // widget (library.body LazyColumn, any future Lazy
                // user-dropped widget) in Column.verticalScroll hands the
                // child maxHeight = Infinity and Compose aborts measure.
                // library.body manages its own scroll via LazyColumn; the
                // slot distributes its bounded height among children.
                SlotRenderer(
                    SurfaceId(SURFACE),
                    SlotId("body"),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    spacing  = 8.dp,
                )
            }

            importError?.let { err ->
                Text(
                    text     = err,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = CelestiaTheme.colors.error,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 24.dp, end = 96.dp, bottom = 34.dp),
                )
            }

            FloatingActionButton(
                onClick        = { startImport() },
                containerColor = CelestiaTheme.colors.primary,
                contentColor   = Color.White,
                modifier       = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            ) {
                if (importing) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Symbol(NxIcon.Add, contentDescription = s.browseImport)
                }
            }
        }
    }
}

private const val SURFACE = "library"
