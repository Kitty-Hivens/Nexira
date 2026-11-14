package hivens.ui.widgets.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hivens.core.data.PackInstance
import hivens.launcher.platform.PlatformPaths
import hivens.ui.Screen
import hivens.ui.nx.NxButton
import hivens.ui.components.DestructiveConfirmDialog
import hivens.ui.i18n.LocalStrings
import hivens.ui.platform.SystemActions
import hivens.ui.screens.library.PackCard
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import androidx.compose.foundation.layout.padding
import hivens.ui.widgets.Commands
import hivens.ui.widgets.Sources
import hivens.widget.api.rememberProps
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import hivens.ui.theme.NxInk

@Serializable
data class LibraryBodyProps(
    @PropLabel("widget.library.body.emptyTitle") val emptyTitle: String = "",
    @PropLabel("widget.library.body.emptyText")
    val emptyText: String = "",
)

// Single widget covers both populated list and empty state. Slot-level
// branching would force the layout graph to know about appState, which
// belongs to navigation, not layout. Self-gating keeps the slot stable
// across the empty -> populated transition.
// The ceiling is load-bearing: this lists lazily, and a lazy list cannot be
// measured against an unbounded axis. Generous, because this is the library's
// whole content pane and a bound under it would cut the list short.
@Widget(
    id = "library.body",
    displayName = "widget.library.body",
    propsClass = LibraryBodyProps::class,
    minWidth = 320, minHeight = 200,
    maxWidth = 2400, maxHeight = 1600,
    needs = ["nav.open", "packs.library", "pack.delete"],
)
@Composable
fun LibraryBody(instance: WidgetInstance) {
    val p = instance.rememberProps<LibraryBodyProps>()
    val navigate = rememberScopedCommand(Commands.Navigate) ?: return
    val instances = rememberScopedSource(Sources.PackLibrary).valueOrNull() ?: return
    // Runs on the app's level, not this composition: leaving the Library mid-delete
    // cancelled it halfway through the tree.
    val deletePack = rememberScopedCommand(Commands.DeletePack) ?: return
    val s = LocalStrings.current
    val paths: PlatformPaths = koinInject()
    var pendingDelete by remember { mutableStateOf<PackInstance?>(null) }

    if (instances.isEmpty()) {
        LibraryEmpty(
            title    = p.emptyTitle.ifBlank { s.libraryEmptyTitle },
            body     = p.emptyText.ifBlank { s.libraryEmptyBody },
            onBrowse = { navigate(Screen.Browse) },
        )
    } else {
        LibraryList(
            instances    = instances,
            onOpenDetail = { navigate(Screen.PackDetail(it.id)) },
            onOpenFolder = { SystemActions.openFolder(instanceDirOf(paths, it).toString()) },
            onDelete     = { pendingDelete = it },
        )
    }

    pendingDelete?.let { target ->
        DestructiveConfirmDialog(
            title        = s.packCardDeleteTitle,
            body         = s.packCardDeleteBody,
            confirmLabel = s.editorDelete,
            onConfirm    = {
                // Files first, registry second (locked jar -> keep the entry, no
                // orphaned data); ordering lives in PackInstanceService now.
                deletePack(target.id)
            },
            onDismiss    = { pendingDelete = null },
        )
    }
}

private fun instanceDirOf(paths: PlatformPaths, instance: PackInstance): Path =
    paths.dataDir.resolve("instances").resolve(instance.instanceDirName)

@Composable
private fun LibraryList(
    instances: List<PackInstance>,
    onOpenDetail: (PackInstance) -> Unit,
    onOpenFolder: (PackInstance) -> Unit,
    onDelete: (PackInstance) -> Unit,
) {
    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = instances, key = { it.id }) { instance ->
            PackCard(
                instance     = instance,
                onOpenDetail = { onOpenDetail(instance) },
                onOpenFolder = { onOpenFolder(instance) },
                onDelete     = { onDelete(instance) },
            )
        }
    }
}

@Composable
private fun LibraryEmpty(title: String, body: String, onBrowse: () -> Unit) {
    val s = LocalStrings.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // A panel the size of what it says. The words are the whole screen here, and
        // on the bare page they lay over whatever the wallpaper had in the middle.
        NxSurface(SurfaceKind.Panel, shape = MaterialTheme.shapes.large) {
            Column(
                modifier            = Modifier.padding(horizontal = 32.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text       = title,
                    style      = MaterialTheme.typography.titleLarge,
                    color      = NxInk.main,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text      = body,
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = NxInk.quiet,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.widthIn(max = 360.dp),
                )
                NxButton(label = s.browseOpen, onClick = onBrowse)
            }
        }
    }
}
