package hivens.ui.screens.detail.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import hivens.core.api.interfaces.IMirrorPackClient
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.update.VersionChannel
import hivens.launcher.PackOperation
import hivens.launcher.PackOperationKind
import hivens.launcher.PackOperationPhase
import hivens.launcher.PackOperationService
import hivens.ui.components.ChannelChip
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxNavRowContent
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativeColor
import org.koin.compose.koinInject
import java.nio.file.Path

/**
 * The floating pack-settings window: a scrimmed overlay hosting a section rail
 * on the left and the selected section's controls on the right -- the global
 * Settings "by sections" grammar, but as a transient panel over the pack detail
 * rather than a nav route.
 *
 * Sized purely by fraction of the app window (no dp caps): a big monitor gets a
 * proportionally big panel instead of a fixed island. Esc and the scrim both
 * dismiss. The footer is a layout-stable status strip for the section-launched
 * async work (an update apply, a failed check), so progress never reflows the
 * panes (Rule 6).
 *
 * The long operations it narrates belong to [PackOperationService], not to this
 * composition: reopening the window over a repair that is still running finds it
 * and picks the narration back up, where window-local state would have shown an
 * idle footer and offered to start a second one.
 *
 * A pack instance is the unit of edit: sections mutate it and flow the result
 * back through [onInstanceChange] (which persists and refreshes the hero), so
 * there is no separate form-state blob -- each control is a `copy` + `put`.
 */
@Composable
fun PackSettingsWindow(
    pack: PackInstance,
    instanceDir: Path,
    onInstanceChange: (PackInstance) -> Unit,
    onDismiss: () -> Unit,
    onOpenVersions: () -> Unit = {},
) {
    val s = LocalStrings.current
    val style = LocalStyle.current
    PuppetScreen("PackSettings.${pack.id}")

    val isMirror = pack.packRef.origin == PackOrigin.Mirror
    val categories = remember(isMirror) {
        PackSettingsCategory.entries.filter { isMirror || !it.mirrorOnly }
    }
    var selected by remember(pack.id) { mutableStateOf(PackSettingsCategory.General) }
    // A detach mid-session drops the Version section; fall back so the pane never
    // dispatches a category the rail no longer shows.
    if (selected !in categories) selected = PackSettingsCategory.General

    val operations: PackOperationService = koinInject()
    val inFlight by operations.operations.collectAsState()
    val operation = inFlight[pack.id]
    // A check is short and belongs to the window, so its failure is a window-local
    // line rather than an entry in the app-scoped registry.
    var notice by remember(pack.id) { mutableStateOf<String?>(null) }

    // The result of a finished operation is read here and nowhere else, so it is
    // dropped when the window goes: a repair from twenty minutes ago has nothing
    // to say to the next visit. A running one is left alone -- closing the window
    // is not what ends it.
    DisposableEffect(pack.id) {
        onDispose { operations.dismiss(pack.id) }
    }

    // Scrim: click outside dismisses; the card swallows clicks so a stray tap
    // inside does not close the window. Esc closes from anywhere in the overlay
    // (the scrim holds focus for it).
    val scrim = remember { MutableInteractionSource() }
    val card = remember { MutableInteractionSource() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                    onDismiss(); true
                } else {
                    false
                }
            }
            .clickable(scrim, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        NxSurface(
            level = NxSurfaceLevel.Raised,
            // Clear + fully opaque: no frost coat (glass off) and a solid body
            // (opaque forces alpha 1 instead of the 0.92 dark bleed-through), so
            // the scrim never reads through the window.
            glass = false,
            opaque = true,
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .fillMaxHeight(0.90f)
                .clip(RoundedCornerShape(style.cardCorner))
                .clickable(card, indication = null, onClick = {}),
        ) {
            Column(Modifier.fillMaxSize()) {
                WindowHeader(pack = pack, isMirror = isMirror, onDismiss = onDismiss)

                Row(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 16.dp)) {
                    // ── Rail (the global Settings nav grammar) ────────────
                    Column(
                        modifier = Modifier.width(200.dp).fillMaxHeight().padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        categories.forEach { category ->
                            val isSelected = category == selected
                            PuppetClick("packSettings.category.${category.name}") { selected = category }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(style.cardCorner))
                                    .background(
                                        if (isSelected) NxTheme.colors.primary.copy(alpha = 0.18f)
                                        else Color.Transparent,
                                    )
                                    .clickable { selected = category }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NxNavRowContent(
                                    icon = category.icon,
                                    label = category.label(s),
                                    isSelected = isSelected,
                                )
                            }
                        }
                    }

                    // ── Pane ──────────────────────────────────────────────
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when (selected) {
                            PackSettingsCategory.General ->
                                PackGeneralSection(pack, onInstanceChange)
                            PackSettingsCategory.Runtime ->
                                PackRuntimeSection(pack, instanceDir, onInstanceChange)
                            PackSettingsCategory.Version ->
                                PackVersionSection(pack, operation, onInstanceChange, onOpenVersions, onNotice = { notice = it })
                            PackSettingsCategory.Content ->
                                PackContentSection(pack, onInstanceChange)
                            PackSettingsCategory.Data ->
                                PackDataSection(pack, instanceDir, operation, onInstanceChange, onDismiss)
                        }
                    }
                }

                FooterStatus(operation, notice)
            }
        }
    }
}

/**
 * Identity header: pack avatar + name, the installed build with its channel for
 * a mirror pack, and close. Channel data arrives best-effort (offline settings
 * stay fully usable; the chips just do not render).
 */
@Composable
private fun WindowHeader(pack: PackInstance, isMirror: Boolean, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    val mirror: IMirrorPackClient = koinInject()
    val installed = pack.pinnedPackVersion ?: pack.packRef.version

    // Best-effort: offline (or a faked client) just means no channel chip.
    val installedChannel by produceState<VersionChannel?>(null, pack.id, installed) {
        if (!isMirror || installed.isNullOrBlank()) return@produceState
        value = runCatching { mirror.fetchManifestVersion(pack.packRef.id, installed).versionChannel }.getOrNull()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeaderAvatar(pack)
        Column(Modifier.weight(1f)) {
            Text(
                pack.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                s.packSettingsTitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
        if (isMirror && installed != null) {
            NxMetaChip("v$installed", tone = NxMetaChipTone.Surface)
            installedChannel?.let { ChannelChip(it) }
        }
        PuppetClick("packSettings.close") { onDismiss() }
        NxIconButton(
            icon = NxIcon.Close,
            contentDescription = s.packSettingsClose,
            onClick = onDismiss,
        )
    }
}

@Composable
private fun HeaderAvatar(pack: PackInstance) {
    val initials = pack.displayName
        .split(' ', '-', '_')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifEmpty { "?" }
    val fallbackTint = NxTheme.colors.decorativeColor(pack.id)
    val box = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
    SubcomposeAsyncImage(
        model              = pack.iconUrl,
        contentDescription = null,
        contentScale       = ContentScale.Crop,
        modifier           = box,
        loading            = { Box(Modifier.fillMaxSize().background(fallbackTint)) },
        error              = {
            Box(Modifier.fillMaxSize().background(fallbackTint), contentAlignment = Alignment.Center) {
                Text(initials, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        },
    )
}

/**
 * Layout-stable footer strip: the instance's long operation, or -- when it has
 * none -- whatever the window itself has to report.
 */
@Composable
private fun FooterStatus(operation: PackOperation?, notice: String?) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    Box(
        modifier = Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 18.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val phase = operation?.phase
        when {
            phase is PackOperationPhase.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (phase.total > 0) {
                    LinearProgressIndicator(
                        progress = { phase.current.toFloat() / phase.total },
                        modifier = Modifier.width(140.dp),
                        color    = colors.primary,
                    )
                    Text(
                        text     = when (operation.kind) {
                            PackOperationKind.Update -> s.packVersionsApplying(phase.current, phase.total, phase.path)
                            PackOperationKind.Repair -> s.packSettingsRepairProgress(phase.current, phase.total, phase.path)
                        },
                        style    = MaterialTheme.typography.labelSmall,
                        color    = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.width(140.dp), color = colors.primary)
                }
            }
            phase is PackOperationPhase.Updated -> Text(
                text  = s.packVersionsApplied(phase.version),
                style = MaterialTheme.typography.labelSmall,
                color = colors.success,
            )
            phase is PackOperationPhase.Repaired -> Text(
                text  = s.packSettingsRepairDone(phase.checked, phase.repaired),
                style = MaterialTheme.typography.labelSmall,
                color = colors.success,
            )
            phase is PackOperationPhase.Failed -> FooterError(s.packVersionsFailed(phase.message))
            notice != null -> FooterError(notice)
        }
    }
}

@Composable
private fun FooterError(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelSmall,
        color    = NxTheme.colors.error,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
