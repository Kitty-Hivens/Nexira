package hivens.ui.screens.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import hivens.launcher.InstallPhase
import hivens.launcher.PackImportService
import hivens.launcher.PackInstallService
import hivens.launcher.imports.LocalPackCreator
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.ui.AppState
import hivens.ui.Screen
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxContextMenu
import hivens.ui.nx.NxField
import hivens.ui.nx.NxMenuItem
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetField
import hivens.ui.puppet.PuppetScreen
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.Motion
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Dimens
import hivens.ui.utils.pickFile
import hivens.ui.utils.rememberFileDialogSettings
import hivens.ui.widgets.library.LibraryContext
import hivens.ui.widgets.library.LocalLibraryContext
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.nio.file.Path
import java.util.UUID

/**
 * Library = user's collection of installed packs. The bottom-right action opens
 * a menu: create a from-scratch local pack or import one from a local archive.
 * Both run through the app-scoped [PackInstallService] so a runtime download
 * survives the dialog closing, and both open the new instance when done -- a
 * created pack lands on its Content tab, where the Modrinth mod browser and
 * local-jar add fill it in.
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

    // A finished create opens the new instance's detail (Content tab), then
    // evicts the snapshot.
    LaunchedEffect(createSnap?.phase) {
        val phase = createSnap?.phase
        val key = createKey
        if (key != null && phase is InstallPhase.Succeeded) {
            installService.dismiss(key)
            createKey = null
            onScreenChange(Screen.PackDetail(phase.instanceId))
        }
    }

    val importDialogSettings = rememberFileDialogSettings(s.browseImport)

    fun startImport() {
        scope.launch {
            val picked = pickFile(
                type     = FileKitType.File(extensions = listOf("mrpack", "zip")),
                settings = importDialogSettings,
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
            // Centred under the same ceiling Browse uses: past a point the extra
            // width of a wide monitor stops being room and starts stretching the
            // rows, which keep their height while their width tracks the window.
            // Its own Box so the error overlay below keeps its own alignment.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.fillMaxHeight()
                        .widthIn(max = Dimens.contentMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                ) {
                    SlotRenderer(SurfaceId(SURFACE), SlotId("header"), modifier = Modifier.fillMaxWidth(), spacing = 8.dp)
                    SlotRenderer(SurfaceId(SURFACE), SlotId("body"), modifier = Modifier.weight(1f).fillMaxWidth(), spacing = 8.dp)
                }
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
                NxContextMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    NxMenuItem(label = s.libraryNewLocalPack, icon = NxIcon.Add) { menuOpen = false; showCreate = true }
                    NxMenuItem(label = s.libraryImportPack, icon = NxIcon.Download) { menuOpen = false; startImport() }
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

/** Name + Minecraft version (picked from Mojang's list) + loader, for a from-scratch local pack. */
@Composable
private fun NewLocalPackDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, mc: String, loader: String?, loaderVersion: String) -> Unit,
) {
    val s = LocalStrings.current
    val provisioner: RuntimeProvisioner = koinInject()

    var name by remember { mutableStateOf("") }
    var mc by remember { mutableStateOf("") }
    var mcMenuOpen by remember { mutableStateOf(false) }
    var showSnapshots by remember { mutableStateOf(false) }
    var loaderVersion by remember { mutableStateOf("") }
    var versions by remember { mutableStateOf<List<String>>(emptyList()) }
    val loaders = remember { listOf("Vanilla" to null, "Fabric" to "fabric", "Forge" to "forge", "NeoForge" to "neoforge", "Quilt" to "quilt") }
    var loaderSel by remember { mutableStateOf(0) }

    // Smart default name from the loader + version ("Fabric 1.20.1"); the name
    // field is optional and falls back to it.
    val defaultName = remember(loaderSel, mc) {
        listOf(loaders[loaderSel].first, mc.trim()).filter { it.isNotBlank() }.joinToString(" ")
    }
    val effectiveName = name.ifBlank { defaultName }
    val canCreate = mc.isNotBlank()

    LaunchedEffect(Unit) {
        versions = runCatching { withContext(Dispatchers.IO) { provisioner.availableMinecraftVersions() } }.getOrDefault(emptyList())
    }
    // Releases only by default (a plain a.b or a.b.c id); snapshots / rc / pre are
    // behind a toggle, so the common case is not buried under nightly builds.
    val matches = remember(mc, versions, showSnapshots) {
        val release = Regex("""^\d+\.\d+(\.\d+)?$""")
        val pool = if (showSnapshots) versions else versions.filter { release.matches(it) }
        (if (mc.isBlank()) pool else pool.filter { it.contains(mc.trim(), ignoreCase = true) }).take(60)
    }

    // Unfold on open: the scrim fades in and the card arrives. Both are motion
    // roles, so a still style collapses the unfold to instant on its own.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scrimAlpha by animateFloatAsState(if (shown) 0.72f else 0f, animationSpec = Motion.fade, label = "scrim")

    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
          AnimatedVisibility(
              visible = shown,
              enter = Motion.emphasis.enter,
              exit  = Motion.emphasis.exit,
          ) {
            NxSurface(
                level = NxSurfaceLevel.Floating,
                // Opaque, not glass: a modal sits over a dark scrim, so there is
                // nothing behind it to frost -- glass would read as a flat muddy
                // panel.
                blurDp = 0f,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(0.9f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Symbol(NxIcon.Inventory2, contentDescription = null, tint = NxTheme.colors.primary, size = 22.dp)
                        Text(s.libraryNewLocalPack, style = MaterialTheme.typography.titleMedium, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                    }

                    FieldLabel(s.createPackName)
                    NxField(value = name, onValueChange = { name = it }, placeholder = defaultName.ifBlank { s.createPackName }, modifier = Modifier.fillMaxWidth())
                    PuppetField("createPack.name", name) { name = it }

                    FieldLabel(s.createPackMc)
                    Box {
                        NxField(
                            value = mc,
                            onValueChange = { mc = it; mcMenuOpen = true },
                            placeholder = "1.20.1",
                            // Open the picker on focus (a click focuses the field) rather than a
                            // clickable -- clickable's default hover/press state layer is an
                            // unclipped square whose corners poke past the field's rounded shape.
                            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) mcMenuOpen = true },
                        )
                        NxContextMenu(expanded = mcMenuOpen && versions.isNotEmpty(), onDismissRequest = { mcMenuOpen = false }) {
                            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                                matches.forEach { v ->
                                    NxMenuItem(label = v, selected = v == mc) { mc = v; mcMenuOpen = false }
                                }
                            }
                            NxMenuItem(
                                label = if (showSnapshots) s.createPackHideSnapshots else s.createPackShowSnapshots,
                                icon = if (showSnapshots) NxIcon.VisibilityOff else NxIcon.Visibility,
                            ) { showSnapshots = !showSnapshots }
                        }
                    }
                    PuppetField("createPack.mc", mc) { mc = it; mcMenuOpen = true }

                    FieldLabel(s.createPackLoader)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        loaders.forEachIndexed { i, (label, _) ->
                            NxChoiceChip(label = label, selected = loaderSel == i) { loaderSel = i }
                        }
                    }

                    if (loaders[loaderSel].second != null) {
                        FieldLabel(s.createPackLoaderVersion)
                        NxField(value = loaderVersion, onValueChange = { loaderVersion = it }, placeholder = s.createPackLoaderVersion, modifier = Modifier.fillMaxWidth())
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        NxButton(label = s.createPackCancel, onClick = onDismiss, style = NxButtonStyle.Tertiary, compact = true)
                        NxButton(
                            label = s.createPackConfirm,
                            onClick = { onCreate(effectiveName.trim(), mc.trim(), loaders[loaderSel].second, loaderVersion.trim()) },
                            style = NxButtonStyle.Primary,
                            icon = NxIcon.Add,
                            enabled = canCreate,
                            compact = true,
                        )
                        PuppetClick("createPack.create", enabled = canCreate) {
                            onCreate(effectiveName.trim(), mc.trim(), loaders[loaderSel].second, loaderVersion.trim())
                        }
                    }
                }
            }
          }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = NxTheme.colors.textSecondary)
}

private const val SURFACE = "library"
