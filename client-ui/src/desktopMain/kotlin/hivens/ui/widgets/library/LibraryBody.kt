package hivens.ui.widgets.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.launcher.platform.PlatformPaths
import hivens.ui.Screen
import hivens.ui.components.DestructiveConfirmDialog
import hivens.ui.i18n.LocalStrings
import hivens.ui.platform.SystemActions
import hivens.ui.screens.library.PackCard
import hivens.ui.theme.CelestiaTheme
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

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
@Widget(id = "library.body", displayName = "widget.library.body", propsClass = LibraryBodyProps::class)
@Composable
fun LibraryBody(instance: WidgetInstance) {
    val p = instance.rememberProps<LibraryBodyProps>()
    val ctx = LocalLibraryContext.current
    val s = LocalStrings.current
    val repo: IPackRepository = koinInject()
    val paths: PlatformPaths = koinInject()
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<PackInstance?>(null) }
    val instances by remember { repo.observe() }.collectAsState(initial = emptyList())

    if (instances.isEmpty()) {
        LibraryEmpty(
            title    = p.emptyTitle.ifBlank { s.libraryEmptyTitle },
            body     = p.emptyText.ifBlank { s.libraryEmptyBody },
            onBrowse = { ctx.onScreenChange(Screen.Browse) },
        )
    } else {
        LibraryList(
            instances    = instances,
            onOpenDetail = { ctx.onScreenChange(Screen.PackDetail(it.id)) },
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
                scope.launch {
                    withContext(Dispatchers.IO) { deleteInstanceDir(instanceDirOf(paths, target)) }
                    repo.delete(target.id)
                }
            },
            onDismiss    = { pendingDelete = null },
        )
    }
}

private fun instanceDirOf(paths: PlatformPaths, instance: PackInstance): Path =
    paths.dataDir.resolve("instances").resolve(instance.instanceDirName)

/** Recursive delete, deepest-first so directories are empty before removal; best-effort per entry. */
private fun deleteInstanceDir(dir: Path) {
    if (!Files.exists(dir)) return
    Files.walk(dir).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { Files.delete(path) } }
    }
}

@Composable
private fun LibraryList(
    instances: List<PackInstance>,
    onOpenDetail: (PackInstance) -> Unit,
    onOpenFolder: (PackInstance) -> Unit,
    onDelete: (PackInstance) -> Unit,
) {
    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(bottom = 16.dp),
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text       = title,
                style      = MaterialTheme.typography.titleLarge,
                color      = CelestiaTheme.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text      = body,
                style     = MaterialTheme.typography.bodyMedium,
                color     = CelestiaTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier  = Modifier.widthIn(max = 360.dp),
            )
            Button(
                onClick = onBrowse,
                shape   = MaterialTheme.shapes.small,
                colors  = ButtonDefaults.buttonColors(
                    containerColor = CelestiaTheme.colors.primary,
                    contentColor   = Color.White,
                ),
            ) { Text(s.browseOpen, fontWeight = FontWeight.SemiBold) }
        }
    }
}
