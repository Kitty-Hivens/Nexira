package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * What the dropdown work actually looks like.
 *
 * Every sheet is one arrangement the running app can produce: a menu of verbs, a
 * list of answers, a list too long for the window, a menu that had to flip, and the
 * select in both of its states. The pointer is driven for the hover sheets, because
 * a hover state nobody has looked at is a hover state nobody has checked.
 */
class DropdownProbe {

    private val density = 2f

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(
        name: String,
        wDp: Int,
        hDp: Int,
        dark: Boolean = true,
        hover: Offset? = null,
        click: Offset? = null,
        frames: Int = 45,
        body: @Composable BoxScope.() -> Unit,
    ) {
        val scene = ImageComposeScene(
            width = (wDp * density).toInt(),
            height = (hDp * density).toInt(),
            density = Density(density),
        ) {
            NxTheme(useDarkTheme = dark) {
                Box(Modifier.fillMaxSize().background(NxTheme.colors.background).padding(Spacing.s16)) { body() }
            }
        }
        // The popup unfolds over several frames, so the clock is advanced by hand;
        // one render() would catch it at the initial scale of its enter transition.
        var t = 0L
        scene.render(t)
        // A press and release, for the controls that have to be OPENED rather than
        // handed expanded = true: a select builds its own popup, asks for focus and
        // scrolls to its answer, and none of that runs on a sheet that skips the click.
        click?.let {
            scene.sendPointerEvent(PointerEventType.Enter, it * density)
            scene.sendPointerEvent(PointerEventType.Move, it * density)
            scene.sendPointerEvent(PointerEventType.Press, it * density)
            scene.sendPointerEvent(PointerEventType.Release, it * density)
        }
        hover?.let {
            scene.sendPointerEvent(PointerEventType.Enter, it * density)
            scene.sendPointerEvent(PointerEventType.Move, it * density)
        }
        var img = scene.render(t)
        repeat(frames) {
            t += 16_000_000L
            img = scene.render(t)
        }
        scene.close()
        File("build/render").mkdirs()
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let {
            File("build/render/dd-$name.png").writeBytes(it)
        }
    }

    /** A stand-in trigger, so the sheet shows what the menu is hanging off. */
    @Composable
    private fun Trigger(width: Int = 36, height: Int = 36) {
        Box(Modifier.size(width.dp, height.dp).background(NxTheme.colors.primary.copy(alpha = 0.35f)))
    }

    @Test
    fun probe() {
        // A menu of verbs, both palettes. Icons, a shortcut hint, a destructive row.
        for (dark in listOf(true, false)) {
            sheet("actions-${if (dark) "dark" else "light"}", 300, 260, dark = dark) {
                Box(Modifier.align(Alignment.TopEnd)) {
                    Trigger()
                    NxContextMenu(expanded = true, onDismissRequest = {}) {
                        NxMenuItem(label = "Open file", icon = NxIcon.FolderOpen, hint = "Ctrl+O") {}
                        NxMenuItem(label = "Reveal in folder", icon = NxIcon.Folder) {}
                        NxMenuDivider()
                        NxMenuItem(label = "Duplicate", icon = NxIcon.ContentCopy) {}
                        NxMenuItem(label = "Delete", icon = NxIcon.Delete, destructive = true, hint = "Del") {}
                    }
                }
            }
        }

        // The same menu with the pointer on its third row: hover is a pill inside the
        // menu, not a band running into the rounded corners.
        sheet("actions-hover", 300, 260, hover = Offset(232f, 96f)) {
            Box(Modifier.align(Alignment.TopEnd)) {
                Trigger()
                NxContextMenu(expanded = true, onDismissRequest = {}) {
                    NxMenuItem(label = "Open file", icon = NxIcon.FolderOpen, hint = "Ctrl+O") {}
                    NxMenuItem(label = "Reveal in folder", icon = NxIcon.Folder) {}
                    NxMenuDivider()
                    NxMenuItem(label = "Duplicate", icon = NxIcon.ContentCopy) {}
                    NxMenuItem(label = "Delete", icon = NxIcon.Delete, destructive = true, hint = "Del") {}
                }
            }
        }

        // A list of answers: every row carries a radio, so the column reads as one
        // question before the pointer moves.
        for (dark in listOf(true, false)) {
            sheet("choice-${if (dark) "dark" else "light"}", 300, 240, dark = dark) {
                Box(Modifier.align(Alignment.TopEnd)) {
                    Trigger()
                    NxContextMenu(expanded = true, onDismissRequest = {}, align = NxMenuAlign.End) {
                        NxMenuSection("Language")
                        listOf("English", "Русский", "Deutsch", "日本語 (alpha)").forEachIndexed { i, name ->
                            NxMenuItem(label = name, selected = i == 1, mark = NxMenuMark.Radio) {}
                        }
                    }
                }
            }
        }

        // Sixty versions and a footer that switches what the list contains. The body
        // scrolls; the footer does not go with it.
        sheet("long-with-footer", 300, 420) {
            Box(Modifier.align(Alignment.TopStart)) {
                Trigger(width = 200)
                NxContextMenu(
                    expanded = true,
                    onDismissRequest = {},
                    align = NxMenuAlign.Start,
                    minWidth = 200.dp,
                    maxHeight = 240.dp,
                    footer = { NxMenuItem(label = "Show snapshots", icon = NxIcon.Visibility) {} },
                ) {
                    (0 until 60).forEach { i -> NxMenuItem(label = "1.2$i.1", selected = i == 3) {} }
                }
            }
        }

        // A very long label: the menu stops at its cap and the row ellipsizes rather
        // than the popup crossing the window.
        sheet("wide-label", 460, 200) {
            Box(Modifier.align(Alignment.TopEnd)) {
                Trigger()
                NxContextMenu(expanded = true, onDismissRequest = {}) {
                    NxMenuItem(label = "Export the whole instance including every world and its backups", icon = NxIcon.Upload) {}
                    NxMenuItem(label = "Short one", icon = NxIcon.Save, hint = "Ctrl+S") {}
                }
            }
        }

        // Near the bottom edge the menu flips above the trigger. The scale origin has
        // to follow it, which is what this sheet is for.
        sheet("flip-up", 300, 260) {
            Box(Modifier.align(Alignment.BottomEnd)) {
                Trigger()
                NxContextMenu(expanded = true, onDismissRequest = {}) {
                    NxMenuItem(label = "Open file", icon = NxIcon.FolderOpen) {}
                    NxMenuItem(label = "Stop", icon = NxIcon.Stop) {}
                    NxMenuItem(label = "Remove", icon = NxIcon.Delete, destructive = true) {}
                }
            }
        }

        // The select, closed and open, against the field it replaces.
        for (dark in listOf(true, false)) {
            sheet("select-closed-${if (dark) "dark" else "light"}", 320, 120, dark = dark) {
                Column {
                    Text("Home view", color = NxTheme.colors.textSecondary)
                    Spacer(Modifier.height(Spacing.s6))
                    NxSelect(
                        options = listOf("New", "Classic"),
                        selected = "New",
                        onSelect = {},
                        label = { it },
                        modifier = Modifier.width(220.dp),
                    )
                }
            }
        }

        // Opened by an actual press on the trigger, so the real popup runs: its own
        // focus request, its scroll to the answer in force, its keyboard highlight.
        for (dark in listOf(true, false)) {
            sheet(
                "select-open-${if (dark) "dark" else "light"}",
                320, 300,
                dark = dark,
                click = Offset(126f, 64f),
            ) {
                Column {
                    Text("Language", color = NxTheme.colors.textSecondary)
                    Spacer(Modifier.height(Spacing.s6))
                    NxSelect(
                        options  = listOf("English", "Русский", "Deutsch", "日本語 (alpha)"),
                        selected = "Deutsch",
                        onSelect = {},
                        label    = { it },
                        modifier = Modifier.width(220.dp),
                    )
                }
            }
        }

        // A select whose list is longer than its cap: it has to open scrolled to the
        // answer rather than to the top of the list.
        sheet("select-long", 320, 340, click = Offset(126f, 64f)) {
            Column {
                Text("Minecraft", color = NxTheme.colors.textSecondary)
                Spacer(Modifier.height(Spacing.s6))
                NxSelect(
                    options   = (0 until 40).map { "1.$it.1" },
                    selected  = "1.31.1",
                    onSelect  = {},
                    label     = { it },
                    maxHeight = 200.dp,
                    modifier  = Modifier.width(220.dp),
                )
            }
        }

        // Four frames in: where the unfold STARTS. Down-growing from the trigger's
        // own centre, and up-growing from its bottom edge when the menu had to flip.
        sheet("unfold-early-down", 300, 260, frames = 4) {
            Box(Modifier.align(Alignment.TopEnd)) {
                Trigger()
                NxContextMenu(expanded = true, onDismissRequest = {}) {
                    NxMenuItem(label = "Open file", icon = NxIcon.FolderOpen) {}
                    NxMenuItem(label = "Reveal in folder", icon = NxIcon.Folder) {}
                    NxMenuItem(label = "Duplicate", icon = NxIcon.ContentCopy) {}
                }
            }
        }
        sheet("unfold-early-up", 300, 260, frames = 4) {
            Box(Modifier.align(Alignment.BottomEnd)) {
                Trigger()
                NxContextMenu(expanded = true, onDismissRequest = {}) {
                    NxMenuItem(label = "Open file", icon = NxIcon.FolderOpen) {}
                    NxMenuItem(label = "Stop", icon = NxIcon.Stop) {}
                    NxMenuItem(label = "Remove", icon = NxIcon.Delete, destructive = true) {}
                }
            }
        }

        // A narrow list under a wide field: matchAnchorWidth is what stops it
        // reading as a menu that belongs to something else on the screen.
        sheet("match-anchor-width", 460, 300) {
            Box(Modifier.align(Alignment.TopStart)) {
                Trigger(width = 400, height = 34)
                NxContextMenu(
                    expanded = true,
                    onDismissRequest = {},
                    align = NxMenuAlign.Start,
                    matchAnchorWidth = true,
                    maxHeight = 180.dp,
                ) {
                    listOf("1.20.1", "1.20.4", "1.21", "1.21.1").forEach {
                        NxMenuItem(label = it, selected = it == "1.21", mark = NxMenuMark.Radio) {}
                    }
                }
            }
        }

        // The bar itself, which only shows while the pointer is in the list.
        sheet("long-scrollbar", 300, 420, hover = Offset(120f, 120f)) {
            Box(Modifier.align(Alignment.TopStart)) {
                Trigger(width = 200)
                NxContextMenu(
                    expanded = true,
                    onDismissRequest = {},
                    align = NxMenuAlign.Start,
                    minWidth = 200.dp,
                    maxHeight = 240.dp,
                    footer = { NxMenuItem(label = "Show snapshots", icon = NxIcon.Visibility) {} },
                ) {
                    (0 until 60).forEach { i -> NxMenuItem(label = "1.2$i.1", selected = i == 3) {} }
                }
            }
        }
    }
}
