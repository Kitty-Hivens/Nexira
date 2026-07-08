package hivens.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxVerticalScrollbar
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalMonoFamily
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Two-pane file browser scoped to a single instance directory. Left
 * pane is a lazily-loaded folder tree; right pane previews the
 * currently-selected file. Paths are resolved against the provided
 * root and the preview pane refuses to read anything outside it --
 * defensive guard against a symlink that points up the tree.
 *
 * Supported preview types:
 *  - Text files (extension whitelist): rendered as monospace,
 *    truncated past [TEXT_PREVIEW_MAX_BYTES] so opening a multi-MB
 *    log doesn't stall the UI.
 *  - Images (png/jpg/gif/webp/bmp): rendered through Coil's
 *    [AsyncImage] which caches the bitmap.
 *  - Everything else: file info card with size + an "open externally"
 *    button that hands off to the OS via `Desktop.open`.
 */
@Composable
fun FileBrowserPane(rootDir: Path, modifier: Modifier = Modifier) {
    val s = LocalStrings.current

    if (!rootDir.isDirectory()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text  = s.fileBrowserNoRoot,
                style = MaterialTheme.typography.bodyMedium,
                color = NxTheme.colors.textSecondary,
            )
        }
        return
    }

    // Real path used as the safety boundary for selection. Resolving
    // symlinks here means a selection through a symlink that points
    // outside the instance gets rejected (the realPath comparison
    // below catches it).
    val rootReal = remember(rootDir) { runCatching { rootDir.toRealPath() }.getOrDefault(rootDir) }

    var selected by remember { mutableStateOf<Path?>(null) }
    // Per-path expansion as a SnapshotStateMap: granular reactivity, so a
    // single subfolder toggle invalidates only the rows that read that
    // specific path's expansion state. Earlier mutableStateOf<Set<Path>>
    // implementation didn't reliably re-render children for tester on
    // Hyprland builds; the state-map form removes the structural-equality
    // ambiguity by tracking per-key reads explicitly.
    val expanded = remember { mutableStateMapOf<Path, Boolean>().apply { put(rootDir, true) } }
    val rows = flattenTree(rootDir, expanded)

    Row(modifier = modifier.fillMaxSize()) {
        // Left: tree.
        val hover = remember { MutableInteractionSource() }
        val hovered by hover.collectIsHoveredAsState()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(MaterialTheme.shapes.medium)
                .background(glassSurfaceAlpha(0.55f))
                .hoverable(hover),
        ) {
            val listState = rememberLazyListState()
            LazyColumn(
                state               = listState,
                modifier            = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(
                    items = rows,
                    key   = { it.path.toAbsolutePath().toString() },
                ) { node ->
                    FileTreeRow(
                        node          = node,
                        isSelected    = selected == node.path,
                        isExpanded    = expanded[node.path] == true,
                        onToggleExpand = {
                            expanded[node.path] = expanded[node.path] != true
                        },
                        onSelect = {
                            if (node.path.isRegularFile()) {
                                val real = runCatching { node.path.toRealPath() }.getOrNull()
                                if (real != null && real.startsWith(rootReal)) selected = node.path
                            }
                        },
                    )
                }
            }
            NxVerticalScrollbar(
                adapter  = rememberScrollbarAdapter(listState),
                revealed = hovered || listState.isScrollInProgress,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }

        Spacer(Modifier.width(12.dp))

        // Right: preview.
        Box(
            modifier = Modifier
                .weight(2f)
                .fillMaxHeight()
                .clip(MaterialTheme.shapes.medium)
                .background(glassSurfaceAlpha(0.55f))
                .padding(16.dp),
        ) {
            val picked = selected
            if (picked == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text  = s.fileBrowserPickAFile,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NxTheme.colors.textSecondary,
                    )
                }
            } else {
                FilePreview(file = picked)
            }
        }
    }
}

// ── Tree flattening ────────────────────────────────────────────────────────

private val log = org.slf4j.LoggerFactory.getLogger("FileBrowserPane")

private data class TreeRow(val path: Path, val depth: Int, val isDir: Boolean, val isEmpty: Boolean = false)

private fun flattenTree(root: Path, expanded: Map<Path, Boolean>): List<TreeRow> {
    val out = mutableListOf<TreeRow>()
    addNode(root, 0, expanded, out)
    return out
}

private fun addNode(node: Path, depth: Int, expanded: Map<Path, Boolean>, out: MutableList<TreeRow>) {
    val isDir = node.isDirectory()
    out += TreeRow(path = node, depth = depth, isDir = isDir)
    if (isDir && expanded[node] == true) {
        val children = try {
            Files.list(node).use { stream ->
                stream.toList().sortedWith(compareBy({ !it.isDirectory() }, { it.name.lowercase() }))
            }
        } catch (e: Exception) {
            // Most-common cause: filesystem permission on a system-junction
            // directory under Windows AppData. Without the log line a silent
            // "expand does nothing" was indistinguishable from the bug we
            // had before the SnapshotStateMap rewrite.
            log.warn("Failed to list children of {}: {}", node, e.message)
            emptyList()
        }
        if (children.isEmpty()) {
            // Sentinel row so the user sees the expansion took effect even
            // when the dir is genuinely empty -- otherwise the chevron
            // flipped silently and the row looked broken.
            out += TreeRow(path = node.resolve(".empty"), depth = depth + 1, isDir = false, isEmpty = true)
        } else {
            children.forEach { addNode(it, depth + 1, expanded, out) }
        }
    }
}

// ── Tree row composable ────────────────────────────────────────────────────

@Composable
private fun FileTreeRow(
    node: TreeRow,
    isSelected: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelect: () -> Unit,
) {
    val rowBg = if (isSelected) NxTheme.colors.primary.copy(alpha = 0.25f)
                else Color.Transparent

    val s = LocalStrings.current
    val clickHandler: () -> Unit = when {
        node.isEmpty -> ({})              // empty-folder placeholder is read-only
        node.isDir   -> onToggleExpand
        else         -> onSelect
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(rowBg)
            .clickable(onClick = clickHandler)
            .padding(start = (12 * node.depth).dp, top = 4.dp, bottom = 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            node.isEmpty -> Spacer(Modifier.size(20.dp))
            node.isDir -> {
                Symbol(icon = if (isExpanded) NxIcon.ExpandLess else NxIcon.ExpandMore,
                    contentDescription = null,
                    tint               = NxTheme.colors.textSecondary,
                    modifier           = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Symbol(icon = NxIcon.Folder,
                    contentDescription = null,
                    tint               = NxTheme.colors.primary.copy(alpha = 0.85f),
                    modifier           = Modifier.size(16.dp),
                )
            }
            else -> {
                Spacer(Modifier.size(20.dp))
                Symbol(icon = fileIconFor(node.path),
                    contentDescription = null,
                    tint               = NxTheme.colors.textSecondary,
                    modifier           = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (node.isEmpty) {
            Text(
                text       = s.fileBrowserEmptyFolder,
                style      = MaterialTheme.typography.bodySmall,
                color      = NxTheme.colors.textSecondary.copy(alpha = 0.7f),
                fontStyle  = FontStyle.Italic,
            )
        } else {
            Text(
                text       = node.path.name,
                style      = MaterialTheme.typography.bodySmall,
                color      = NxTheme.colors.textPrimary,
                fontWeight = if (node.isDir) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

private fun fileIconFor(path: Path): IconKey {
    val ext = path.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in TEXT_EXTENSIONS -> NxIcon.Description
        in IMAGE_EXTENSIONS -> NxIcon.Image
        else -> NxIcon.InsertDriveFile
    }
}

// ── Preview pane ───────────────────────────────────────────────────────────

@Composable
private fun FilePreview(file: Path) {
    val ext = file.name.substringAfterLast('.', "").lowercase()

    when (ext) {
        in TEXT_EXTENSIONS -> TextPreview(file)
        in IMAGE_EXTENSIONS -> ImagePreview(file)
        else -> BinaryPreview(file)
    }
}

@Composable
private fun TextPreview(file: Path) {
    val s = LocalStrings.current
    val state = produceState<TextLoadResult?>(initialValue = null, key1 = file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val size = file.fileSize()
                val limited = size > TEXT_PREVIEW_MAX_BYTES
                val bytes = if (limited) {
                    Files.newInputStream(file).use { stream ->
                        val buf = ByteArray(TEXT_PREVIEW_MAX_BYTES.toInt())
                        var read = 0
                        while (read < buf.size) {
                            val n = stream.read(buf, read, buf.size - read)
                            if (n < 0) break
                            read += n
                        }
                        buf.copyOf(read)
                    }
                } else {
                    Files.readAllBytes(file)
                }
                TextLoadResult(text = String(bytes, Charsets.UTF_8), truncated = limited, totalSize = size)
            }.getOrElse {
                TextLoadResult(text = "[error: ${it.message}]", truncated = false, totalSize = 0L)
            }
        }
    }.value

    if (state == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                color       = NxTheme.colors.primary.copy(alpha = 0.6f),
                strokeWidth = 2.dp,
                modifier    = Modifier.size(24.dp),
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PreviewHeader(file = file, sizeLabel = file.fileSizeLabel())
        Spacer(Modifier.height(8.dp))
        if (state.truncated) {
            Text(
                text  = s.fileBrowserTextTruncated(TEXT_PREVIEW_MAX_BYTES / 1024),
                style = MaterialTheme.typography.labelSmall,
                color = NxTheme.colors.error,
            )
            Spacer(Modifier.height(6.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text       = state.text,
                style      = MaterialTheme.typography.bodySmall,
                color      = NxTheme.colors.textPrimary,
                fontFamily = LocalMonoFamily.current,
            )
        }
    }
}

@Composable
private fun ImagePreview(file: Path) {
    Column(modifier = Modifier.fillMaxSize()) {
        PreviewHeader(file = file, sizeLabel = file.fileSizeLabel())
        Spacer(Modifier.height(8.dp))
        AsyncImage(
            model              = file.toFile(),
            contentDescription = null,
            modifier           = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun BinaryPreview(file: Path) {
    val s = LocalStrings.current
    Column(
        modifier            = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PreviewHeader(file = file, sizeLabel = file.fileSizeLabel())
        Spacer(Modifier.height(8.dp))
        Box(
            modifier         = Modifier.fillMaxWidth().height(140.dp).clip(MaterialTheme.shapes.medium).background(glassSurfaceAlpha(0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text  = s.fileBrowserBinaryHint,
                style = MaterialTheme.typography.bodySmall,
                color = NxTheme.colors.textSecondary,
            )
        }
        Button(
            onClick        = { runCatching { Desktop.getDesktop().open(file.toFile()) } },
            shape          = MaterialTheme.shapes.small,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            colors         = ButtonDefaults.buttonColors(
                containerColor = NxTheme.colors.primary,
                contentColor   = Color.White,
            ),
        ) {
            Symbol(NxIcon.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(s.fileBrowserOpenExternally, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PreviewHeader(file: Path, sizeLabel: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Symbol(fileIconFor(file), contentDescription = null, tint = NxTheme.colors.primary, modifier = Modifier.size(18.dp))
        Text(
            text       = file.name,
            style      = MaterialTheme.typography.titleSmall,
            color      = NxTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text  = sizeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = NxTheme.colors.textSecondary,
        )
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────

private data class TextLoadResult(val text: String, val truncated: Boolean, val totalSize: Long)

private fun Path.fileSizeLabel(): String = runCatching {
    when (val n = fileSize()) {
        in 0..1023L         -> "$n B"
        in 1024..1_048_575L -> "${n / 1024} KB"
        else                -> "${n / 1_048_576L} MB"
    }
}.getOrDefault("?")

private val TEXT_EXTENSIONS = setOf(
    "txt", "log", "json", "toml", "yml", "yaml", "xml",
    "cfg", "conf", "properties", "md", "ini", "gradle",
    "kt", "java", "kts", "csv", "tsv", "html", "css", "js",
)

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

private const val TEXT_PREVIEW_MAX_BYTES = 256L * 1024L
