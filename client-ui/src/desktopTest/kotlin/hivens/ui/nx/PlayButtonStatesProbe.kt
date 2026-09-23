package hivens.ui.nx

import androidx.compose.foundation.background
import hivens.ui.theme.Spacing
import hivens.ui.icons.Symbol
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.core.launch.LaunchControlMode
import hivens.ui.components.LaunchControl
import hivens.ui.widgets.sample.LaunchTile
import hivens.ui.effects.pixelArtBackground
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.GermanStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.i18n.RussianStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.settle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativePair
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * Every state a launch control can be in, on the ground it is actually drawn over.
 *
 * A design probe, kept in the tree on purpose: it is the source of the pictures the
 * launch control's look is decided from, and a PNG cannot be re-rendered under a new
 * palette or a new locale. Each row is the pack hero's bottom strip, the same pixel
 * art and scrim the hero paints, with the control pinned to its end.
 */
class PlayButtonStatesProbe {

    /** What the control is doing, as a proposal would have to be told. */
    private enum class Kind { Ready, Waiting, Unavailable, Problem }

    /** One state of the control, in the words and glyph the launch control model gives it. */
    private data class Cell(
        val name: String,
        val label: (AppStrings) -> String,
        val icon: IconKey,
        val enabled: Boolean = true,
        val busy: Boolean = false,
        val kind: Kind = Kind.Ready,
        /** Known share done, or null for work whose size is not known. */
        val progress: Float? = null,
    )

    private val cells = listOf(
        Cell("play", { it.packDetailPlay }, NxIcon.PlayArrow),
        Cell("sign in", { it.packDetailPlayLoginRequired }, NxIcon.Person),
        Cell("preparing", { it.packPlayWait }, NxIcon.PlayArrow, busy = true, kind = Kind.Waiting),
        Cell("running", { it.packPlayExit }, NxIcon.Stop),
        Cell("updating 42%", { it.launchBlockUpdating }, NxIcon.Update, busy = true, kind = Kind.Waiting, progress = 0.42f),
        Cell("repairing 80%", { it.launchBlockRepairing }, NxIcon.Build, busy = true, kind = Kind.Waiting, progress = 0.8f),
        Cell("recovering", { it.launchBlockRecovering }, NxIcon.History, busy = true, kind = Kind.Waiting),
        Cell("mods 3 of 10", { it.launchBlockUpdatingContent }, NxIcon.Sync, busy = true, kind = Kind.Waiting, progress = 0.3f),
        Cell("missing", { it.launchBlockMissing }, NxIcon.Warning, enabled = false, kind = Kind.Problem),
        Cell("other game", { it.launchBlockOtherRunning }, NxIcon.HourglassEmpty, enabled = false, kind = Kind.Unavailable),
    )

    /** The hero's bottom strip: pixel art under the same 0.4 scrim, control at the end. */
    @Composable
    private fun HeroStrip(seed: String, content: @Composable () -> Unit) {
        val (a, b) = NxTheme.colors.decorativePair(seed)
        Box(Modifier.width(STRIP_W.dp).height(STRIP_H.dp).pixelArtBackground(seed, a, b)) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
            Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) { content() }
        }
    }

    /**
     * The quick-launch card's row: the theme's own surface, no art and no scrim. What
     * the control draws here is read against the theme, not against a darkened picture.
     */
    @Composable
    private fun SurfaceStrip(height: Int, content: @Composable () -> Unit) {
        Box(
            Modifier.width(STRIP_W.dp).height(height.dp).background(NxTheme.colors.surface),
            contentAlignment = Alignment.CenterEnd,
        ) { Box(Modifier.padding(horizontal = 18.dp)) { content() } }
    }

    @Composable
    private fun Sheet(dark: Boolean, onSurface: Boolean = false, stripH: Int = STRIP_H, button: @Composable (Cell, AppStrings) -> Unit) {
        NxTheme(useDarkTheme = dark) {
            Box(Modifier.fillMaxSize().background(NxTheme.colors.background).padding(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cells.forEach { cell ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(cell.name, Modifier.width(110.dp), style = MaterialTheme.typography.labelMedium, color = NxTheme.colors.textSecondary)
                            listOf(RussianStrings, GermanStrings).forEach { strings ->
                                CompositionLocalProvider(LocalStrings provides strings) {
                                    if (onSurface) SurfaceStrip(stripH) { button(cell, strings) }
                                    else HeroStrip("${cell.name}-${strings.hashCode()}") { button(cell, strings) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The inks a proposal draws with, per ground. Over media the ground is always a
     * darkened picture whatever the theme, so its inks are fixed light-on-dark; over a
     * surface they come from the theme.
     */
    private class Inks(
        val plate: Color,
        val onPlate: Color,
        val quiet: Color,
        val quietLine: Color,
        val warn: Color,
        val caption: Color,
    )

    @Composable
    private fun inks(onSurface: Boolean): Inks {
        val c = NxTheme.colors
        val dark = c.background.luminance() < 0.5f
        val plate = if (dark) Color(0xFF121318) else Color.White
        val onPlate = if (dark) Color.White else Color.Black
        return if (onSurface) {
            Inks(
                plate = if (dark) plate else Color(0xFF121318),
                onPlate = if (dark) onPlate else Color.White,
                quiet = c.textSecondary,
                quietLine = c.textSecondary.copy(alpha = 0.35f),
                warn = c.warnAccent,
                caption = c.textSecondary,
            )
        } else {
            Inks(
                plate = plate,
                onPlate = onPlate,
                quiet = Color.White.copy(alpha = 0.72f),
                quietLine = Color.White.copy(alpha = 0.38f),
                warn = Color(0xFFE0B341),
                caption = Color.White.copy(alpha = 0.82f),
            )
        }
    }

    /** Where an indeterminate band sits in the one frame a probe takes, as a share of the plate. */
    private val bandPhase = 0.35f

    /**
     * The progress layer inside a plate: a fill up to [progress], or for work of unknown
     * size a band a third of the plate wide at [bandPhase], faded at both ends.
     */
    private fun Modifier.progressLayer(progress: Float?, ink: Color): Modifier = drawBehind {
        if (progress != null) {
            drawRect(ink, size = Size(size.width * progress.coerceIn(0f, 1f), size.height))
        } else {
            val w = size.width / 3f
            val x = (size.width + w) * bandPhase - w
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, ink, Color.Transparent), startX = x, endX = x + w),
                topLeft = Offset(x, 0f),
                size = Size(w, size.height),
            )
        }
    }

    /**
     * V2, the plate carries the progress. The label stays inside the plate and names the
     * state; work in progress fills the plate from the start, with the share in figures
     * at the end, the way a game launcher's Play turns into its own progress bar.
     */
    @Composable
    private fun ProgressPlate(cell: Cell, s: AppStrings, onSurface: Boolean) {
        val k = inks(onSurface)
        val shape = MaterialTheme.shapes.medium
        val filled = cell.kind == Kind.Ready || cell.kind == Kind.Waiting
        val content = when (cell.kind) {
            Kind.Ready -> k.onPlate
            Kind.Waiting -> k.onPlate.copy(alpha = 0.78f)
            Kind.Unavailable -> k.quiet
            Kind.Problem -> k.quiet
        }
        Row(
            Modifier
                .widthIn(min = MIN_PLATE.dp)
                .clip(shape)
                .then(if (filled) Modifier.background(k.plate) else Modifier)
                .then(if (cell.kind == Kind.Waiting) Modifier.progressLayer(cell.progress, k.onPlate.copy(alpha = 0.14f)) else Modifier)
                .border(if (filled) 1.dp else 1.5.dp, if (filled) k.onPlate.copy(alpha = 0.18f) else if (cell.kind == Kind.Problem) k.warn.copy(alpha = 0.7f) else k.quietLine, shape)
                .padding(horizontal = Spacing.s20, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        ) {
            Symbol(
                cell.icon,
                tint = if (cell.kind == Kind.Problem) k.warn else content,
                size = 18.dp,
                fill = if (cell.icon == NxIcon.PlayArrow || cell.icon == NxIcon.Stop) 1f else 0f,
            )
            Text(cell.label(s), color = content, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            cell.progress?.let {
                Text(
                    "${(it * 100).toInt()}%",
                    color = content.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    /**
     * V3, the plate keeps one size and the words move out of it. Ready states keep the
     * verb in the plate; every other state puts its sentence in a caption beside the
     * plate and leaves the plate a glyph, or the share in figures, at the width of the
     * verb, so nothing next to the control is pushed about as the state changes.
     */
    @Composable
    private fun CaptionPlate(cell: Cell, s: AppStrings, onSurface: Boolean) {
        val k = inks(onSurface)
        val shape = MaterialTheme.shapes.medium
        val ready = cell.kind == Kind.Ready
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s12)) {
            if (!ready) {
                Text(
                    cell.label(s),
                    color = if (cell.kind == Kind.Problem) k.warn else k.caption,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            val filled = ready || cell.kind == Kind.Waiting
            Box(
                Modifier
                    .width(if (ready) androidx.compose.ui.unit.Dp.Unspecified else MIN_PLATE.dp)
                    .widthIn(min = MIN_PLATE.dp)
                    .clip(shape)
                    .then(if (filled) Modifier.background(k.plate) else Modifier)
                    .then(if (cell.kind == Kind.Waiting) Modifier.progressLayer(cell.progress, k.onPlate.copy(alpha = 0.14f)) else Modifier)
                    .border(if (filled) 1.dp else 1.5.dp, if (filled) k.onPlate.copy(alpha = 0.18f) else if (cell.kind == Kind.Problem) k.warn.copy(alpha = 0.7f) else k.quietLine, shape)
                    .padding(horizontal = Spacing.s20, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                val content = if (filled) k.onPlate else k.quiet
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    when {
                        ready -> {
                            Symbol(cell.icon, tint = content, size = 18.dp, fill = if (cell.icon == NxIcon.PlayArrow || cell.icon == NxIcon.Stop) 1f else 0f)
                            Text(cell.label(s), color = content, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                        cell.progress != null -> Text(
                            "${(cell.progress * 100).toInt()}%",
                            color = content,
                            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.Bold,
                        )
                        else -> Symbol(
                            cell.icon,
                            tint = if (cell.kind == Kind.Problem) k.warn else content.copy(alpha = if (filled) 0.78f else 1f),
                            size = 18.dp,
                            fill = if (cell.icon == NxIcon.PlayArrow || cell.icon == NxIcon.Stop) 1f else 0f,
                        )
                    }
                }
            }
        }
    }

    private fun write(name: String, stripH: Int = STRIP_H, content: @Composable () -> Unit) {
        val widthDp = 16 + 110 + 12 + 2 * STRIP_W + 12 + 16
        val heightDp = 16 + cells.size * (stripH + 8) + 16
        val scene = ImageComposeScene((widthDp * DENSITY).toInt(), (heightDp * DENSITY).toInt(), density = Density(DENSITY), content = content)
        try {
            val t = scene.settle(frames = 8)
            val png = scene.render(t).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
            val out = Path.of("build/render", "$name.png")
            Files.createDirectories(out.parent)
            Files.write(out, png.bytes)
            println("probe written: ${out.toAbsolutePath()}")
        } finally {
            scene.close()
        }
    }

    private fun Cell.tone(): PlayTone = when (kind) {
        Kind.Ready -> PlayTone.Ready
        Kind.Waiting -> PlayTone.Waiting
        Kind.Unavailable -> PlayTone.Unavailable
        Kind.Problem -> PlayTone.Problem
    }

    /**
     * The shipped control, both layouts, on both grounds and in both themes. What the
     * proposals below were drawn to decide, as it is actually built.
     */
    @Test
    fun `the shipped control in every state`() {
        for (dark in listOf(true, false)) {
            val theme = if (dark) "dark" else "light"
            for (layout in PlayLayout.entries) {
                val name = layout.name.lowercase()
                write("play-states-final-$name-hero-$theme") {
                    Sheet(dark) { cell, s ->
                        PlayButton(cell.label(s), {}, icon = cell.icon, tone = cell.tone(), progress = cell.progress, ground = PlayGround.Media, layout = layout)
                    }
                }
                write("play-states-final-$name-surface-$theme") {
                    Sheet(dark, onSurface = true) { cell, s ->
                        PlayButton(cell.label(s), {}, icon = cell.icon, tone = cell.tone(), progress = cell.progress, ground = PlayGround.Surface, layout = layout)
                    }
                }
            }
        }
    }

    /** The home launch tile in every state, which speaks the same language at its own size. */
    @Test
    fun `the launch tile in every state`() {
        for (dark in listOf(true, false)) {
            write("play-states-final-tile-${if (dark) "dark" else "light"}", stripH = TILE_H) {
                Sheet(dark, onSurface = true, stripH = TILE_H) { cell, s ->
                    Box(Modifier.width(STRIP_W.dp - 36.dp)) {
                        LaunchTile(
                            LaunchControl(
                                mode = LaunchControlMode.Play,
                                block = null,
                                label = cell.label(s),
                                icon = cell.icon,
                                tone = cell.tone(),
                                progress = cell.progress,
                                onClick = {},
                            ),
                            packName = "Industrial",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `two proposals in every state, on the hero and on a surface`() {
        for (dark in listOf(true, false)) {
            val theme = if (dark) "dark" else "light"
            write("play-states-v2-hero-$theme") { Sheet(dark) { cell, s -> ProgressPlate(cell, s, onSurface = false) } }
            write("play-states-v3-hero-$theme") { Sheet(dark) { cell, s -> CaptionPlate(cell, s, onSurface = false) } }
            write("play-states-v2-surface-$theme") { Sheet(dark, onSurface = true) { cell, s -> ProgressPlate(cell, s, onSurface = true) } }
            write("play-states-v3-surface-$theme") { Sheet(dark, onSurface = true) { cell, s -> CaptionPlate(cell, s, onSurface = true) } }
        }
    }

    private companion object {
        /** The width of the plain Play plate, measured off the current control at this locale's longest verb. */
        const val MIN_PLATE = 116
        const val STRIP_W = 420
        const val STRIP_H = 64
        const val TILE_H = 112
        const val DENSITY = 1.5f
    }
}
