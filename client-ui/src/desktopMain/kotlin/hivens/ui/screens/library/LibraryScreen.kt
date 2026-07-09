package hivens.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.launcher.InstallPhase
import hivens.launcher.PackImportService
import hivens.launcher.PackInstallService
import hivens.launcher.imports.LocalPackCreator
import hivens.ui.AppState
import hivens.ui.Screen
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.NxTheme
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
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import org.koin.compose.koinInject
import java.nio.file.Path
import java.util.UUID

/**
 * Library = user's collection of installed packs. A bottom-right action opens
 * either the pack importer (local `.mrpack`/`.zip`) or the from-scratch creator
 * (an empty local pack to author by hand). Both run through the app-scoped
 * [PackInstallService] so a create's runtime download survives the dialog
 * closing, and both open the new instance when done.
 */
@Composable
fun LibraryScreen(
    appState: AppState,
    onScreenChange: (Screen) -> Unit,
) {
    PuppetScreen("Library")

    val s = LocalStrings.current
    val importService: PackImportService = koinInject()
    val installService: PackInstallService = koinInject()
    val creator: LocalPackCreator = koinInject()
    val scope = rememberCoroutineScope()

    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var createKey by remember { mutableStateOf<String?>(null) }

    val installs by installService.installs.collectAsState()
    val createSnap = createKey?.let { installs[it] }
    val creating = createSnap?.phase is InstallPhase.Running
    val createError = (createSnap?.phase as? InstallPhase.Failed)?.message

    // A finished create opens the new instance's detail (where the Content tab's
    // Modrinth browser + local-jar add fill the pack in), then evicts the snapshot.
    LaunchedEffect(createSnap?.phase) {
        val phase = createSnap?.phase
        val key = createKey
        if (key != null && phase is InstallPhase.Succeeded) {
            installService.dismiss(key)
            createKey = null
            onScreenChange(Screen.PackDetail(phase.instanceId))
        }
    }

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

    fun startCreate(name: String, mc: String, loader: String?, loaderVersion: String) {
        showCreate = false
        createKey = installService.run(key = "create:$name:${UUID.randomUUID().toString().take(8)}", title = name) { reserve, progress ->
            creator.create(name, mc, loader, loaderVersion, reserve, progress)
        }
    }

    val ctx = remember(appState, onScreenChange) {
        LibraryContext(appState = appState, onScreenChange = onScreenChange)
    }
    CompositionLocalProvider(LocalLibraryContext provides ctx) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
                SlotRenderer(SurfaceId(SURFACE), SlotId("header"), modifier = Modifier.fillMaxWidth(), spacing = 8.dp)
                // No verticalScroll on the body slot: library.body owns its own
                // LazyColumn scroll; wrapping it hands maxHeight = Infinity and
                // Compose aborts measure.
                SlotRenderer(SurfaceId(SURFACE), SlotId("body"), modifier = Modifier.weight(1f).fillMaxWidth(), spacing = 8.dp)
            }

            (importError ?: createError)?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = NxTheme.colors.error,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 24.dp, end = 96.dp, bottom = 34.dp),
                )
            }

            Box(Modifier.align(Alignment.BottomEnd).padding(24.dp)) {
                FloatingActionButton(
                    onClick = { menuOpen = true },
                    containerColor = NxTheme.colors.primary,
                    contentColor = Color.White,
                ) {
                    if (importing || creating) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Symbol(NxIcon.Add, contentDescription = s.libraryAddAction)
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(s.libraryNewLocalPack) },
                        onClick = { menuOpen = false; showCreate = true },
                    )
                    DropdownMenuItem(
                        text = { Text(s.libraryImportPack) },
                        onClick = { menuOpen = false; startImport() },
                    )
                }
                PuppetClick("library.newLocalPack") { menuOpen = false; showCreate = true }
                PuppetClick("library.importPack") { menuOpen = false; startImport() }
            }
        }
    }

    if (showCreate) {
        NewLocalPackDialog(onDismiss = { showCreate = false }, onCreate = ::startCreate)
    }
}

/** Name + Minecraft version + loader, for an empty from-scratch local pack. */
@Composable
private fun NewLocalPackDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, mc: String, loader: String?, loaderVersion: String) -> Unit,
) {
    val s = LocalStrings.current
    var name by remember { mutableStateOf("") }
    var mc by remember { mutableStateOf("") }
    var loaderVersion by remember { mutableStateOf("") }
    // Label -> LoaderRegistry id (null = vanilla).
    val loaders = remember { listOf("Vanilla" to null, "Fabric" to "fabric", "Forge" to "forge", "NeoForge" to "neoforge", "Quilt" to "quilt") }
    var loaderSel by remember { mutableStateOf(0) }
    val canCreate = name.isNotBlank() && mc.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.libraryNewLocalPack, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(s.createPackName) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = mc, onValueChange = { mc = it }, singleLine = true,
                    label = { Text(s.createPackMc) }, placeholder = { Text("1.20.1") }, modifier = Modifier.fillMaxWidth())
                Text(s.createPackLoader, style = MaterialTheme.typography.labelMedium, color = NxTheme.colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    loaders.forEachIndexed { i, (label, _) ->
                        FilterChip(selected = loaderSel == i, onClick = { loaderSel = i }, label = { Text(label) })
                    }
                }
                if (loaders[loaderSel].second != null) {
                    OutlinedTextField(value = loaderVersion, onValueChange = { loaderVersion = it }, singleLine = true,
                        label = { Text(s.createPackLoaderVersion) }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canCreate, onClick = { onCreate(name.trim(), mc.trim(), loaders[loaderSel].second, loaderVersion.trim()) }) {
                Text(s.createPackConfirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.createPackCancel) } },
    )
}

private const val SURFACE = "library"
