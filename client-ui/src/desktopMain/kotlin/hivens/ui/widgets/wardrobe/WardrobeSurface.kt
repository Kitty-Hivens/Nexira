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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
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
import hivens.ui.identity.SkinLibrary
import hivens.ui.identity.SkinManager
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
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
    val scope = rememberCoroutineScope()

    var refreshKey by remember { mutableIntStateOf(0) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val skins = remember(refreshKey) { library.list() }
    val selectedBitmap = remember(selectedId, refreshKey) {
        selectedId?.let { library.bytes(it) }?.let(::decodeSkin)
    }
    val scSession = remember(refreshKey, session) { credentials.accountFor(SC_KEY) }

    fun importSkin() {
        scope.launch {
            val picked = FileKit.openFilePicker(type = FileKitType.File(extensions = listOf("png")))
            val file = picked?.path?.let { File(it) } ?: return@launch
            val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() } ?: return@launch
            val entry = withContext(Dispatchers.IO) { library.add(bytes, file.nameWithoutExtension, slim = false, now = System.currentTimeMillis()) }
            selectedId = entry.id
            refreshKey++
        }
    }

    fun applyToSmartyCraft() {
        val sc = scSession ?: return
        val id = selectedId ?: return
        val entry = skins.firstOrNull { it.id == id } ?: return
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { skinRepository.uploadSkin(library.file(id).toFile(), entry.slim, sc) }
                    .getOrElse { it.message ?: "error" }
            }
            busy = false
            if (result == "OK") {
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
            val bmp = selectedBitmap
            if (bmp != null) {
                SkinView3D(bmp, Modifier.fillMaxHeight().width(260.dp), interactive = true, autoSpin = true)
            } else {
                SkinHero(session.playerName, refreshKey, Modifier.width(260.dp).fillMaxHeight(), interactive = true, autoSpin = true)
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                af.ChaosButton(
                    id = "wardrobe_upload_btn",
                    text = s.wardrobeUpload,
                    onClick = { importSkin() },
                    modifier = Modifier,
                    colors = ButtonDefaults.buttonColors(containerColor = CelestiaTheme.colors.primary),
                )
                PuppetClick("wardrobe.upload") { importSkin() }
                if (scSession != null && selectedId != null) {
                    af.ChaosButton(
                        id = "wardrobe_apply_sc_btn",
                        text = s.wardrobeApplySmartycraft,
                        onClick = { if (!busy) applyToSmartyCraft() },
                        modifier = Modifier,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = glassSurfaceAlpha(0.55f),
                            contentColor = CelestiaTheme.colors.textPrimary,
                        ),
                    )
                    PuppetClick("wardrobe.applySmartycraft") { if (!busy) applyToSmartyCraft() }
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.error) }

            if (skins.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.wardrobeEmpty, style = MaterialTheme.typography.bodyMedium, color = CelestiaTheme.colors.textSecondary)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 84.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(skins, key = { it.id }) { entry ->
                        SkinCard(
                            bitmap = remember(entry.id, refreshKey) { library.bytes(entry.id)?.let(::decodeSkin) },
                            name = entry.name,
                            selected = entry.id == selectedId,
                            onClick = { selectedId = entry.id },
                            onDelete = {
                                library.delete(entry.id)
                                if (selectedId == entry.id) selectedId = null
                                refreshKey++
                            },
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
            if (bitmap != null) SkinHead(bitmap, Modifier.fillMaxSize().padding(4.dp))
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
            IconButton(onClick = onDelete, modifier = Modifier.width(24.dp).height(24.dp)) {
                Symbol(NxIcon.Delete, s.accountRemove, tint = CelestiaTheme.colors.textSecondary, modifier = Modifier.width(16.dp).height(16.dp))
            }
        }
    }
}

// 2D head: the 8x8 face at (8,8) plus the hat overlay at (40,8), nearest-neighbour
// scaled. Works for both 64x64 and legacy 64x32 skins.
@Composable
private fun SkinHead(skin: ImageBitmap, modifier: Modifier) {
    Canvas(modifier) {
        val dst = IntSize(size.width.toInt(), size.height.toInt())
        drawImage(skin, srcOffset = IntOffset(8, 8), srcSize = IntSize(8, 8), dstSize = dst, filterQuality = FilterQuality.None)
        drawImage(skin, srcOffset = IntOffset(40, 8), srcSize = IntSize(8, 8), dstSize = dst, filterQuality = FilterQuality.None)
    }
}

private fun decodeSkin(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
