package hivens.ui.widgets.wardrobe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import hivens.core.api.SkinRepository
import hivens.core.data.PackAuthRequirement
import hivens.core.data.SessionData
import hivens.auth.AccountStore
import hivens.ui.components.DestructiveConfirmDialog
import hivens.ui.flexible.Flexible
import hivens.ui.flexible.FlexibleKind
import hivens.ui.i18n.AppStrings
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxSectionHeader
import hivens.ui.nx.NxTooltip
import hivens.ui.surface.NxCard
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.identity.ClanRole
import hivens.ui.identity.ClanRoleProvider
import hivens.ui.identity.DefaultSkinProvider
import hivens.ui.identity.SkinLibrary
import hivens.ui.identity.SkinManager
import hivens.ui.identity.skinContentHash
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.skin3d.Cycles
import hivens.ui.skin3d.PoseSource
import hivens.ui.skin3d.Poses
import hivens.ui.skin3d.SkinFraming
import hivens.ui.skin3d.SkinView3D
import hivens.ui.skin3d.asSource
import hivens.ui.skin3d.layered
import hivens.ui.skin3d.rememberSkinViewState
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalStyle
import hivens.ui.utils.pickFile
import hivens.ui.utils.rememberFileDialogSettings
import hivens.ui.widgets.profile.SkinHero
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.koin.compose.koinInject
import java.io.File
import java.nio.file.Files

private const val SC_KEY = PackAuthRequirement.SmartyCraft.PROVIDER_KEY

// Caption strip under every card. Fixed so a card with a delete button (an
// IconButton is taller than a bare label) lines up with the "+" import tile and
// the read-only defaults -- the grid does not equalize row heights on its own.
private val CardCaptionHeight = 24.dp

// Wardrobe screen -- the skins workspace. Left: the live 3D preview (the picked
// library skin, else the current applied look). Right: the local library --
// import a PNG, pick one to preview, apply it to SmartyCraft, or delete it.
// Mojang apply + default skins + capes build on this.
@Composable
fun WardrobeSurface(session: SessionData?, onBack: () -> Unit) {
    val s = LocalStrings.current
    PuppetScreen("Wardrobe")
    // The window frame carries the visible back arrow, so the screen draws none.
    // Automation has no frame to click, and without this the wardrobe was the one
    // screen a driver could enter and not leave.
    PuppetClick("wardrobe.back") { onBack() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // Title lives in the top-bar breadcrumb now -- no in-screen duplicate.
        NxCard(modifier = Modifier.weight(1f).fillMaxWidth(), level = NxSurfaceLevel.Raised) {
            if (session == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.wardrobeSignedOut, style = MaterialTheme.typography.bodyMedium, color = NxTheme.colors.textSecondary)
                }
            } else {
                Wardrobe(session)
            }
        }
    }
}

// One pass over the library index, read off the UI thread. The library
// doubles as the history -- the last-applied entry (per kind) is active.
private data class WardrobeData(
    val skins: List<SkinLibrary.Entry> = emptyList(),
    val capes: List<SkinLibrary.Entry> = emptyList(),
    val activeSkinId: String? = null,
    val activeCapeId: String? = null,
)

/**
 * Whether the cape block (import tile, cards, apply) renders at all. Only a
 * POSITIVE finding of ineligibility hides it: no SmartyCraft account to apply
 * to, a profile that shows no clan, or a clan role below leader. Unknown
 * fails open with the clan hint -- a false-show costs one failed upload, a
 * false-hide silently locks a legitimate leader out.
 */
internal fun capeSectionVisible(hasScAccount: Boolean, role: ClanRole): Boolean = when {
    !hasScAccount -> false
    role == ClanRole.NoClan || role == ClanRole.NotLeader -> false
    else -> true
}

// The wardrobe's pose presets for the preview model, in chip order.
private enum class WardrobePose { Stand, Wave, Sit, FaceCover, Walk }

private fun WardrobePose.source(): PoseSource = when (this) {
    // Standing breathes -- the same idle the profile hero plays.
    WardrobePose.Stand -> Cycles.idle()
    WardrobePose.Wave -> layered(Poses.Wave.asSource(), Cycles.handWave())
    WardrobePose.Sit -> Poses.Sit.asSource()
    WardrobePose.FaceCover -> Poses.FaceCover.asSource()
    WardrobePose.Walk -> Cycles.walk()
}

private fun WardrobePose.label(s: AppStrings): String = when (this) {
    WardrobePose.Stand -> s.wardrobePoseStand
    WardrobePose.Wave -> s.wardrobePoseWave
    WardrobePose.Sit -> s.wardrobePoseSit
    WardrobePose.FaceCover -> s.wardrobePoseFaceCover
    WardrobePose.Walk -> s.wardrobePoseWalk
}

@Composable
private fun Wardrobe(session: SessionData) {
    val s = LocalStrings.current
    val library: SkinLibrary = koinInject()
    val skinRepository: SkinRepository = koinInject()
    val skinManager: SkinManager = koinInject()
    val credentials: AccountStore = koinInject()
    val defaultSkinProvider: DefaultSkinProvider = koinInject()
    val clanRoles: ClanRoleProvider = koinInject()
    val scope = rememberCoroutineScope()

    var refreshKey by remember { mutableIntStateOf(0) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var selectedCapeId by remember { mutableStateOf<String?>(null) }
    var selectedDefault by remember { mutableStateOf<DefaultSkinProvider.DefaultSkin?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val dialogSettings = rememberFileDialogSettings()

    val data by produceState(WardrobeData(), refreshKey) {
        value = withContext(Dispatchers.IO) {
            WardrobeData(
                skins = library.list(SkinLibrary.Kind.Skin),
                capes = library.list(SkinLibrary.Kind.Cape),
                activeSkinId = library.activeId(SkinLibrary.Kind.Skin),
                activeCapeId = library.activeId(SkinLibrary.Kind.Cape),
            )
        }
    }

    // Decoded library PNGs keyed by entry id. Content per id is immutable
    // (apply never rewrites a file, import mints a new id), so each entry
    // decodes exactly once -- a refresh re-lists the index without re-decoding
    // or re-rasterizing anything already on screen, which is what used to
    // hitch the whole grid on every apply.
    //
    // Decoding is IO and the map is snapshot state: the read happens on the pool,
    // the write on the composition's own thread. Assigning from inside the worker
    // used whatever snapshot the coroutine had inherited and threw once that one
    // had been left behind.
    val bitmaps = remember { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(data) {
        for (entry in data.skins + data.capes) {
            if (bitmaps.containsKey(entry.id)) continue
            val decoded = withContext(Dispatchers.IO) { library.bytes(entry.id)?.let(::decodeSkin) } ?: continue
            bitmaps[entry.id] = decoded
        }
    }

    // Default skins are extracted from a provisioned client jar on the IO pool; the
    // grid stays empty until that resolves (or no client jar carries them yet).
    val defaults by produceState(emptyList()) {
        value = withContext(Dispatchers.IO) { defaultSkinProvider.list() }
    }
    val defaultBitmaps = remember { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(defaults) {
        for (def in defaults) {
            if (defaultBitmaps.containsKey(def.name)) continue
            val decoded = withContext(Dispatchers.IO) {
                runCatching { Files.readAllBytes(def.file) }.getOrNull()?.let(::decodeSkin)
            } ?: continue
            defaultBitmaps[def.name] = decoded
        }
    }

    val selectedBitmap = selectedId?.let { bitmaps[it] }
    val defaultBitmap = selectedDefault?.let { defaultBitmaps[it.name] }

    // A card's whole surface selects and the trash sits inside it, so a near-miss
    // used to be the last thing that happened to an imported file: delete went
    // straight to the library with no confirm and nothing to undo it with.
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    pendingDelete?.let { id ->
        DestructiveConfirmDialog(
            title        = s.wardrobeDeleteTitle,
            body         = s.wardrobeDeleteBody,
            confirmLabel = s.editorDelete,
            onConfirm    = {
                scope.launch {
                    withContext(Dispatchers.IO) { library.delete(id) }
                    bitmaps.remove(id)
                    if (selectedId == id) selectedId = null
                    if (selectedCapeId == id) selectedCapeId = null
                    refreshKey++
                }
            },
            onDismiss    = { pendingDelete = null },
        )
    }
    val scSession = remember(refreshKey, session) { credentials.accountFor(SC_KEY) }

    // Cape capability. Fast path: a fresh login already said "no clan", no
    // network needed. Otherwise the public player page decides -- it works
    // regardless of how stale the persisted session is (pre-field sessions
    // never resolve their clan flag).
    var capeRole by remember { mutableStateOf(ClanRole.Unknown) }
    LaunchedEffect(scSession?.playerName, scSession?.clan, scSession?.clanResolved) {
        capeRole = when {
            scSession == null -> ClanRole.Unknown
            scSession.clanResolved && scSession.clan == null -> ClanRole.NoClan
            else -> clanRoles.eligibility(scSession.playerName)
        }
    }
    val showCapes = capeSectionVisible(scSession != null, capeRole)

    // The current server skin is the player's real look but lives on the server, not the
    // local library -- auto-import it (deduped by pixel content) so it shows among the
    // saved skins and reads as the active one. Runs once per player: a genuine server-side
    // skin change surfaces as a new entry on the next open, an identical one dedups.
    LaunchedEffect(session.playerName) {
        val bytes = skinManager.getRawSkinBytes(session.playerName) ?: return@LaunchedEffect
        val primed = withContext(Dispatchers.IO) {
            // No pixel hash -> no dedup, so skip rather than accumulate a copy per open.
            val sha = skinContentHash(bytes) ?: return@withContext null
            val entry = library.addUnique(bytes, session.playerName, slim = false, now = System.currentTimeMillis(), sha = sha)
            library.markApplied(entry.id, System.currentTimeMillis())
            decodeSkin(bytes)?.let { entry.id to it }
        }
        primed?.let { (id, bitmap) -> bitmaps[id] = bitmap }
        refreshKey++
    }

    fun importInto(kind: SkinLibrary.Kind, select: (String) -> Unit) {
        scope.launch {
            val picked = pickFile(type = FileKitType.File(extensions = listOf("png")), settings = dialogSettings)
            val file = picked?.path?.let { File(it) } ?: return@launch
            val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() } ?: return@launch
            val entry = withContext(Dispatchers.IO) {
                library.add(
                    bytes, file.nameWithoutExtension, slim = false,
                    now = System.currentTimeMillis(), kind = kind, sha = skinContentHash(bytes),
                )
            }
            // Prime the decode cache from the bytes in hand so the preview
            // flips to the import without a placeholder frame.
            withContext(Dispatchers.IO) { decodeSkin(bytes) }?.let { bitmaps[entry.id] = it }
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

    // The preview wears the picked cape (else the active one), so a cape can
    // be inspected on the model before applying. Follows the capability gate:
    // no cape UI, no cape on the model.
    val previewCape = if (showCapes) (selectedCapeId ?: data.activeCapeId)?.let { bitmaps[it] } else null

    // One hoisted view state so a picked pose survives switching skins, and
    // one selected chip driving it. The preview opens on the breathing idle
    // (Stand's animation) and does NOT turntable -- poses are the point now;
    // drag still orbits for inspection.
    val previewState = rememberSkinViewState(initialAnimation = Cycles.idle())
    var pose by remember { mutableStateOf(WardrobePose.Stand) }

    Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        // Preview column: the picked library skin (else the current applied
        // look) with the pose chips under it.
        Column(Modifier.width(260.dp).fillMaxHeight()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val bmp = selectedBitmap ?: defaultBitmap
                if (bmp != null) {
                    SkinView3D(bmp, Modifier.fillMaxSize(), interactive = true, autoSpin = false, cape = previewCape, state = previewState)
                } else {
                    SkinHero(session.playerName, refreshKey, Modifier.fillMaxSize(), interactive = true, autoSpin = false, cape = previewCape, state = previewState)
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) {
                WardrobePose.entries.forEach { p ->
                    NxChoiceChip(label = p.label(s), selected = pose == p) {
                        pose = p
                        previewState.play(p.source())
                    }
                    PuppetClick("wardrobe.pose.${p.name}") {
                        pose = p
                        previewState.play(p.source())
                    }
                }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Constant-height action strip: the apply buttons, the busy
            // spinner and the error line all live INSIDE it, so neither
            // picking a skin nor a failed upload shifts the grid below.
            // A default skin carries no markId (it is not a library entry,
            // so it never enters the applied-history).
            val skinSel: Pair<File, String?>? = selectedId?.let { library.file(it).toFile() to it }
                ?: selectedDefault?.let { it.file.toFile() to null }
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (scSession != null) {
                        skinSel?.let { (file, markId) ->
                            Flexible("wardrobe_apply_sc_btn", FlexibleKind.Button) {
                                NxButton(
                                    label = s.wardrobeApplySmartycraft,
                                    onClick = { if (!busy) applyToSc(file, isCloak = false, markId = markId) },
                                    style = NxButtonStyle.Primary,
                                    enabled = !busy,
                                )
                            }
                            PuppetClick("wardrobe.applySmartycraft") { if (!busy) applyToSc(file, isCloak = false, markId = markId) }
                        }
                        if (showCapes) {
                            selectedCapeId?.let { id ->
                                val capeFile = library.file(id).toFile()
                                Flexible("wardrobe_apply_cape_sc_btn", FlexibleKind.Button) {
                                    NxButton(
                                        label = s.wardrobeApplyCape,
                                        onClick = { if (!busy) applyToSc(capeFile, isCloak = true, markId = id) },
                                        style = NxButtonStyle.Secondary,
                                        enabled = !busy,
                                    )
                                }
                                PuppetClick("wardrobe.applyCapeSmartycraft") { if (!busy) applyToSc(capeFile, isCloak = true, markId = id) }
                            }
                        }
                    }
                    if (busy) {
                        CircularProgressIndicator(
                            color = NxTheme.colors.primary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    error?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = NxTheme.colors.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }

            // One grid, two sections (Modrinth shape). The "+" tile leads each, so a
            // section is never truly empty -- it doubles as the import prompt.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "skins-h", span = { GridItemSpan(maxLineSpan) }) {
                    NxSectionHeader(s.wardrobeSaved, muted = true, modifier = Modifier.padding(top = 4.dp))
                }
                item(key = "add-skin") { AddTile(onClick = { importInto(SkinLibrary.Kind.Skin) { selectedId = it } }) }
                items(data.skins, key = { it.id }) { entry ->
                    SkinCard(
                        bitmap = bitmaps[entry.id],
                        name = entry.name,
                        selected = entry.id == selectedId,
                        isActive = entry.id == data.activeSkinId,
                        onClick = { selectedId = entry.id; selectedDefault = null },
                        onDelete = { pendingDelete = entry.id },
                    )
                }

                // The cape block renders only when the account could actually
                // set one (capability gate above); the clan hint stays for the
                // fail-open Unknown case.
                if (showCapes) {
                    item(key = "capes-h", span = { GridItemSpan(maxLineSpan) }) {
                        NxSectionHeader(s.wardrobeCapes, muted = true, modifier = Modifier.padding(top = 4.dp))
                    }
                    item(key = "capes-hint", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            s.wardrobeCapeClanHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = NxTheme.colors.textSecondary,
                        )
                    }
                    item(key = "add-cape") { AddTile(onClick = { importInto(SkinLibrary.Kind.Cape) { selectedCapeId = it } }) }
                    items(data.capes, key = { it.id }) { entry ->
                        CapeCard(
                            bitmap = bitmaps[entry.id],
                            name = entry.name,
                            selected = entry.id == selectedCapeId,
                            isActive = entry.id == data.activeCapeId,
                            onClick = { selectedCapeId = entry.id },
                            onDelete = { pendingDelete = entry.id },
                        )
                    }
                }

                // Mojang's default skins, read from a provisioned client jar (never
                // bundled). Read-only -- pick to preview / apply, no delete.
                if (defaults.isNotEmpty()) {
                    item(key = "defaults-h", span = { GridItemSpan(maxLineSpan) }) {
                        NxSectionHeader(s.wardrobeDefaults, muted = true, modifier = Modifier.padding(top = 4.dp))
                    }
                    items(defaults, key = { it.name }) { def ->
                        SkinCard(
                            bitmap = defaultBitmaps[def.name],
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
            .background(NxTheme.colors.background.copy(alpha = 0.4f))
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) NxTheme.colors.primary else NxTheme.colors.primary.copy(alpha = 0f),
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
                    tint = NxTheme.colors.success,
                    size = 16.dp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                )
            }
        }
        Row(Modifier.height(CardCaptionHeight), verticalAlignment = Alignment.CenterVertically) {
            CardCaption(name, Modifier.weight(1f, fill = false))
            // Default skins are read-only (not library entries), so no delete.
            onDelete?.let { del ->
                IconButton(onClick = del, modifier = Modifier.size(24.dp)) {
                    Symbol(NxIcon.Delete, s.accountRemove, tint = NxTheme.colors.textSecondary, size = 16.dp)
                }
            }
        }
    }
}

// Card caption that reveals the full name on hover, but only when the label
// is actually cut -- an untruncated caption has nothing to add.
@Composable
private fun CardCaption(name: String, modifier: Modifier = Modifier) {
    var truncated by remember { mutableStateOf(false) }
    NxTooltip(text = name, enabled = truncated, modifier = modifier) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = NxTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { truncated = it.hasVisualOverflow },
        )
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
            .background(NxTheme.colors.surface.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Symbol(NxIcon.Add, s.wardrobeUpload, tint = NxTheme.colors.primary, size = 32.dp)
        }
        Box(Modifier.height(CardCaptionHeight), contentAlignment = Alignment.Center) {
            Text(s.wardrobeUpload, style = MaterialTheme.typography.labelSmall, color = NxTheme.colors.textSecondary, maxLines = 1)
        }
    }
    PuppetClick("wardrobe.upload") { onClick() }
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
            .background(NxTheme.colors.background.copy(alpha = 0.4f))
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) NxTheme.colors.primary else NxTheme.colors.primary.copy(alpha = 0f),
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
                    tint = NxTheme.colors.success,
                    size = 16.dp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                )
            }
        }
        Row(Modifier.height(CardCaptionHeight), verticalAlignment = Alignment.CenterVertically) {
            CardCaption(name, Modifier.weight(1f, fill = false))
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Symbol(NxIcon.Delete, s.accountRemove, tint = NxTheme.colors.textSecondary, size = 16.dp)
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
    runCatching { Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() } }.getOrNull()
