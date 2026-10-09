package hivens.ui.screens.library.content

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.launcher.instance.InstalledContent
import hivens.ui.customization.LocalCustomization
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxIconButton
import hivens.ui.feature.catalogue.project.rememberLinkFollower
import hivens.ui.theme.Motion
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.familyForText
import kotlinx.coroutines.launch

/** Why a row offers less than the player might expect of it. */
internal enum class RowLock {
    /** The pack installs and updates this mod: no switch, no removal, no version. */
    Pack,

    /** An optional mod of the pack: it switches, but removal and versions are the pack's. */
    PackOptional,
}

/**
 * What the pack keeps from a row, on a card of its own.
 *
 * A mod the pack placed has no switch, cannot be removed and has no versions to
 * pick, and a row that simply lacks those controls reads as a fault: the tab used
 * to say why in a banner, and the banner left with the detach button it pointed
 * at. The row's right-click and its overflow open this instead of a menu, since
 * the question a locked row raises is "why", and a list of verbs does not answer
 * it. The verbs the menu had are here as buttons, beside the way out.
 *
 * The lock arrives rather than sits: it lands, rocks on its hinge and fills, the
 * way a latch closes, so the card reads as an answer the moment it opens.
 */
@Composable
internal fun PackLockCard(
    content: InstalledContent,
    lock: RowLock,
    mark: @Composable () -> Unit,
    resolveProject: suspend () -> ModrinthProject?,
    onDetails: () -> Unit,
    onOpenPackSettings: () -> Unit,
    dismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val follow = rememberLinkFollower()
    // The same resolution the menu makes: the declared homepage, else the catalogue
    // page found by the file's hash, and no button while neither is known.
    var page by remember(content.fileName) { mutableStateOf(content.homepageUrl) }
    if (page == null) {
        LaunchedEffect(content.fileName) {
            page = runCatching { resolveProject() }.getOrNull()
                ?.let { "https://modrinth.com/${it.projectType}/${it.slug}" }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        mark()
        Column(Modifier.weight(1f)) {
            Text(
                text       = content.displayName,
                fontFamily = familyForText(content.displayName),
                style      = MaterialTheme.typography.bodyMedium,
                color      = NxInk.main,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            content.version?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = NxInk.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        page?.let { url ->
            NxIconButton(
                icon               = NxIcon.OpenInNew,
                contentDescription = s.contentActionOpenPage,
                onClick            = { dismiss(); follow(url) },
                tint               = NxInk.quiet,
            )
        }
    }

    Box(Modifier.fillMaxWidth().height(1.dp).background(NxInk.line))

    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        LockMark()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text       = s.contentLockedTitle,
                style      = MaterialTheme.typography.titleSmall,
                color      = NxInk.main,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text  = if (lock == RowLock.PackOptional) s.contentLockedOptionalBody else s.contentLockedBody,
                style = MaterialTheme.typography.bodySmall,
                color = NxInk.quiet,
            )
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NxButton(
            label   = s.contentLockedOpenSettings,
            onClick = { dismiss(); onOpenPackSettings() },
            icon    = NxIcon.Settings,
            compact = true,
        )
        NxButton(
            label   = s.contentActionDetails,
            onClick = { dismiss(); onDetails() },
            style   = NxButtonStyle.Secondary,
            icon    = NxIcon.Info,
            compact = true,
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun LockMark() {
    val still = LocalCustomization.current.reduceMotion
    val land = Motion.emphasis
    val ring = Motion.reveal
    val close = Motion.fade
    val lead = NxColor.lead()
    val halo = NxColor.wash(lead, 0.16f)
    // Each from where it ends when motion is off, so a still interface shows the
    // closed lock with no ring rather than the first frame of an arrival.
    val scale = remember { Animatable(if (still) 1f else 0.5f) }
    val rock = remember { Animatable(0f) }
    val fill = remember { Animatable(if (still) 1f else 0f) }
    val pulse = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (still) return@LaunchedEffect
        launch { pulse.animateTo(1f, ring.of()) }
        scale.animateTo(1f, land.of())
        launch { fill.animateTo(1f, close.of()) }
        val d = land.durationMs
        rock.animateTo(
            0f,
            keyframes {
                durationMillis = d
                0f at 0
                -14f at d / 5
                10f at d * 2 / 5
                -5f at d * 3 / 5
            },
        )
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .drawBehind {
                val r = size.minDimension / 2f
                drawCircle(halo, radius = r)
                // One ring outward as the lock lands, then gone.
                val p = pulse.value
                if (p < 1f) {
                    drawCircle(
                        color  = lead.copy(alpha = 0.5f * (1f - p)),
                        radius = r * (1f + 0.45f * p),
                        style  = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(
            icon     = NxIcon.Lock,
            tint     = lead,
            fill     = fill.value,
            size     = 24.dp,
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                rotationZ = rock.value
                // The hinge is the shackle's base, not the middle of the glyph.
                transformOrigin = TransformOrigin(0.5f, 0.35f)
            },
        )
    }
}
