package hivens.ui.widgets.wardrobe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import hivens.core.api.SkinRepository
import hivens.core.data.PackAuthRequirement
import hivens.core.data.SessionData
import hivens.launcher.CredentialsManager
import hivens.ui.components.GlassCard
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.easter.LocalAprilFools
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.identity.DefaultSkinProvider
import hivens.ui.identity.SkinLibrary
import hivens.ui.identity.SkinManager
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.skin3d.SkinFraming
import hivens.ui.skin3d.SkinView3D
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle
import hivens.ui.widgets.profile.SkinHero
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.koin.compose.koinInject
import java.io.File
import java.nio.file.Files

private val SC_KEY = PackAuthRequirement.SmartyCraft.PROVIDER_KEY

// Wardrobe screen -- the skins workspace. Left: the live 3D preview (the picked
// library skin, else the current applied look). Right: the local library --
// import a PNG, pick one to preview, apply it to SmartyCraft, or delete it.
// Mojang apply + default skins + capes build on this.
@Composable
fun WardrobeSurface(session: SessionData?, onBack: () -> Unit) {
    val s = LocalStrings.current
    PuppetScreen("Wardrobe")

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = s.wardrobeTitle,
            style = MaterialTheme.typography.headlineSmall,
            color = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(24.dp))

        GlassCard(modifier = Modifier.weight(1f).fillMaxWidth(), backgroundColor = glassSurfaceAlpha(0.7f)) {
            if (session == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.wardrobeSignedOut, style = MaterialTheme.typography.bodyMedium, color = CelestiaTheme.colors.textSecondary)
                }
            } else {
                Wardrobe(session)
            }
        }
    }
}

@Composable
private fun Wardrobe(session: SessionData) {
    val s = LocalStrings.current
    val af = LocalAprilFools.current
    val library: SkinLibrary = koinInject()
    val skinRepository: SkinRepository = koinInject()
    val skinManager: SkinManager = koinInject()
    val credentials: CredentialsManager = koinInject()
    val defaultSkinProvider: DefaultSkinProvider = koinInject()
    val scope = rememberCoroutineScope()

    var refreshKey by remember { mutableIntStateOf(0) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var selectedCapeId by remember { mutableStateOf<String?>(null) }
    var selectedDefault by remember { mutableStateOf<DefaultSkinProvider.DefaultSkin?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val skins = remember(refreshKey) { library.list(SkinLibrary.Kind.Skin) }
    val capes = remember(refreshKey) { library.list(SkinLibrary.Kind.Cape) }
    // Default skins are extracted from a provisioned client jar on the IO pool; the
    // grid stays empty until that resolves (or no client jar carries them yet).
    val defaults by produceState(emptyList<DefaultSkinProvider.DefaultSkin>()) {
        value = withContext(Dispatchers.IO) { defaultSkinProvider.list() }
    }
    val selectedBitmap = remember(selectedId, refreshKey) {
        selectedId?.let { library.bytes(it) }?.let(::decodeSkin)
    }
    val defaultBitmap = remember(selectedDefault) {
        selectedDefault?.let { runCatching { Files.readAllBytes(it.file) }.getOrNull()?.let(::decodeSkin) }
    }
    val scSession = remember(refreshKey, session) { credentials.accountFor(SC_KEY) }
    // The library doubles as the history -- the last-applied entry (per kind) is active.
    val activeSkinId = remember(refreshKey) { library.activeId(SkinLibrary.Kind.Skin) }
    val activeCapeId = remember(refreshKey) { library.activeId(SkinLibrary.Kind.Cape) }

    fun importInto(kind: SkinLibrary.Kind, select: (String) -> Unit) {
        scope.launch {
            val picked = FileKit.openFilePicker(type = FileKitType.File(extensions = listOf("png")))
            val file = picked?.path?.let { File(it) } ?: return@launch
            val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() } ?: return@launch
            val entry = withContext(Dispatchers.IO) {
                library.add(bytes, file.nameWithoutExtension, slim = false, now = System.currentTimeMillis(), kind = kind)
            }
            select(entry.id)
            refreshKey++
        }
    }

    // Applies a skin or cape file to the signed-in SmartyCraft account -- a cape goes
    // through uploadCloak (isCloak), which the server gates to clan leaders. [markId]
    // stamps the library history when the file is a library entry (null for defaults).
    fun applyToSc(file: File, isCloak: Boolean, markId: String?) {
        val sc = scSession ?: return
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { skinRepository.uploadSkin(file, isCloak = isCloak, session = sc) }
                    .getOrElse { it.message ?: "error" }
            }
            busy = false
            if (result == "OK") {
                markId?.let { library.markApplied(it, System.currentTimeMillis()) }
                skinManager.invalidate(sc.playerName)
                refreshKey++
            } else {
                error = s.profileUploadError(result)
            }
        }
    }

    Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        // Preview: the picked library skin, else the current applied look.
        Box(Modifier.width(260.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            val bmp = selectedBitmap ?: defaultBitmap
            if (bmp != null) {
                SkinView3D(bmp, Modifier.fillMaxHeight().width(260.dp), interactive = true, autoSpin = true)
            } else {
                SkinHero(session.playerName, refreshKey, Modifier.width(260.dp).fillMaxHeight(), interactive = true, autoSpin = true)
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Apply the picked skin (library entry or default) and/or cape to a
            // signed-in SmartyCraft account. A default carries no markId (it is not a
            // library entry, so it never enters the applied-history).
            val skinSel: Pair<File, String?>? = selectedId?.let { library.file(it).toFile() to it }
                ?: selectedDefault?.let { it.file.toFile() to null }
            if (scSession != null && (skinSel != null || selectedCapeId != null)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    skinSel?.let { (file, markId) ->
                        af.ChaosButton(
                            id = "wardrobe_apply_sc_btn",
                            text = s.wardrobeApplySmartycraft,
                            onClick = { if (!busy) applyToSc(file, isCloak = false, markId = markId) },
                            modifier = Modifier,
                            colors = ButtonDefaults.buttonColors(containerColor = CelestiaTheme.colors.primary),
                        )
                        PuppetClick("wardrobe.applySmartycraft") { if (!busy) applyToSc(file, isCloak = false, markId = markId) }
                    }
                    selectedCapeId?.let { id ->
                        val capeFile = library.file(id).toFile()
                        af.ChaosButton(
                            id = "wardrobe_apply_cape_sc_btn",
                            text = s.wardrobeApplyCape,
                            onClick = { if (!busy) applyToSc(capeFile, isCloak = true, markId = id) },
                            modifier = Modifier,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = glassSurfaceAlpha(0.55f),
                                contentColor = CelestiaTheme.colors.textPrimary,
                            ),
                        )
                        PuppetClick("wardrobe.applyCapeSmartycraft") { if (!busy) applyToSc(capeFile, isCloak = true, markId = id) }
                    }
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.error) }

            // One grid, two sections (Modrinth shape). The "+" tile leads each, so a
            // section is never truly empty -- it doubles as the import prompt.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "skins-h", span = { GridItemSpan(maxLineSpan) }) { SectionHeader(s.wardrobeSaved) }
                item(key = "add-skin") { AddTile(onClick = { importInto(SkinLibrary.Kind.Skin) { selectedId = it } }) }
                items(skins, key = { it.id }) { entry ->
                    SkinCard(
                        bitmap = remember(entry.id, refreshKey) { library.bytes(entry.id)?.let(::decodeSkin) },
                        name = entry.name,
                        selected = entry.id == selectedId,
                        isActive = entry.id == activeSkinId,
                        onClick = { selectedId = entry.id; selectedDefault = null },
                        onDelete = {
                            library.delete(entry.id)
                            if (selectedId == entry.id) selectedId = null
                            refreshKey++
                        },
                    )
                }

                item(key = "capes-h", span = { GridItemSpan(maxLineSpan) }) { SectionHeader(s.wardrobeCapes) }
                item(key = "capes-hint", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        s.wardrobeCapeClanHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = CelestiaTheme.colors.textSecondary,
                    )
                }
                item(key = "add-cape") { AddTile(onClick = { importInto(SkinLibrary.Kind.Cape) { selectedCapeId = it } }) }
                items(capes, key = { it.id }) { entry ->
                    CapeCard(
                        bitmap = remember(entry.id, refreshKey) { library.bytes(entry.id)?.let(::decodeSkin) },
                        name = entry.name,
                        selected = entry.id == selectedCapeId,
                        isActive = entry.id == activeCapeId,
                        onClick = { selectedCapeId = entry.id },
                        onDelete = {
                            library.delete(entry.id)
                            if (selectedCapeId == entry.id) selectedCapeId = null
                            refreshKey++
                        },
                    )
                }

                // Mojang's default skins, read from a provisioned client jar (never
                // bundled). Read-only -- pick to preview / apply, no delete.
                if (defaults.isNotEmpty()) {
                    item(key = "defaults-h", span = { GridItemSpan(maxLineSpan) }) { SectionHeader(s.wardrobeDefaults) }
                    items(defaults, key = { it.name }) { def ->
                        SkinCard(
                            bitmap = remember(def.file) { runCatching { Files.readAllBytes(def.file) }.getOrNull()?.let(::decodeSkin) },
                            name = def.name,
                            selected = def == selectedDefault,
                            isActive = false,
                            onClick = { selectedDefault = def; selectedId = null },
                            onDelete = null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkinCard(
    bitmap: ImageBitmap?,
    name: String,
    selected: Boolean,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val s = LocalStrings.current
    val style = LocalStyle.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(style.cardCorner))
            .background(CelestiaTheme.colors.background.copy(alpha = 0.4f))
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) CelestiaTheme.colors.primary else CelestiaTheme.colors.primary.copy(alpha = 0f),
                shape = RoundedCornerShape(style.cardCorner),
            )
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            if (bitmap != null) {
                SkinView3D(bitmap, Modifier.fillMaxSize(), interactive = false, autoSpin = false, framing = SkinFraming.Bust)
            }
            // Active marker -- the most-recently-applied skin (library = history).
            if (isActive) {
                Symbol(
                    NxIcon.CheckCircle, null,
                    tint = CelestiaTheme.colors.success,
                    size = 16.dp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = CelestiaTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Default skins are read-only (not library entries), so no delete.
            onDelete?.let { del ->
                IconButton(onClick = del, modifier = Modifier.size(24.dp)) {
                    Symbol(NxIcon.Delete, s.accountRemove, tint = CelestiaTheme.colors.textSecondary, size = 16.dp)
                }
            }
        }
    }
}

// The "+" tile that opens the PNG import; shaped like a skin card so the grid reads
// as one strip (Modrinth's "Add a skin").
@Composable
private fun AddTile(onClick: () -> Unit) {
    val s = LocalStrings.current
    val style = LocalStyle.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(style.cardCorner))
            .background(glassSurfaceAlpha(0.4f))
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Symbol(NxIcon.Add, s.wardrobeUpload, tint = CelestiaTheme.colors.primary, size = 32.dp)
        }
        Text(s.wardrobeUpload, style = MaterialTheme.typography.labelSmall, color = CelestiaTheme.colors.textSecondary, maxLines = 1)
    }
    PuppetClick("wardrobe.upload") { onClick() }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = CelestiaTheme.colors.textSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun CapeCard(
    bitmap: ImageBitmap?,
    name: String,
    selected: Boolean,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = LocalStrings.current
    val style = LocalStyle.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(style.cardCorner))
            .background(CelestiaTheme.colors.background.copy(alpha = 0.4f))
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) CelestiaTheme.colors.primary else CelestiaTheme.colors.primary.copy(alpha = 0f),
                shape = RoundedCornerShape(style.cardCorner),
            )
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            if (bitmap != null) CapeThumbnail(bitmap, Modifier.fillMaxHeight().aspectRatio(10f / 16f))
            if (isActive) {
                Symbol(
                    NxIcon.CheckCircle, null,
                    tint = CelestiaTheme.colors.success,
                    size = 16.dp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = CelestiaTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Symbol(NxIcon.Delete, s.accountRemove, tint = CelestiaTheme.colors.textSecondary, size = 16.dp)
            }
        }
    }
}

// The cape's front face -- (1,1) size 10x16 on a 64x32 cape, scaled by the HD
// multiple, nearest-neighbour. Legacy 22x17 capes share the (1,1) front origin.
@Composable
private fun CapeThumbnail(cape: ImageBitmap, modifier: Modifier) {
    Canvas(modifier) {
        val k = (cape.width / 64f).coerceAtLeast(1f)
        drawImage(
            cape,
            srcOffset = IntOffset((1 * k).toInt(), (1 * k).toInt()),
            srcSize = IntSize((10 * k).toInt(), (16 * k).toInt()),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.None,
        )
    }
}

private fun decodeSkin(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
