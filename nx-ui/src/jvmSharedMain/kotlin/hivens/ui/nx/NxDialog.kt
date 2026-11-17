package hivens.ui.nx

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.layers.Layer
import hivens.ui.layers.LayerHost
import hivens.ui.layers.layerBody
import hivens.ui.layers.rememberWindowLayers
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.Motion
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxTheme

/**
 * A modal dialog in the window: [content] centred over a scrim that blocks
 * everything opened before it.
 *
 * It is a modal layer of the window rather than a Compose `Dialog`, so it stands in
 * the window's order with everything else: a menu or a tooltip opened from inside
 * it draws above it, and the layer under it waits. It takes the keyboard as it
 * opens, keeps Tab inside, and gives the focus back to where it was when it goes.
 *
 * [onDismissRequest] is what Escape, back and a press on the scrim ask for. Null
 * makes a dialog that only its own buttons close: Escape and back do nothing behind
 * it, and the scrim takes the press without closing it. [dismissOnOutsidePress]
 * false keeps Escape and back but not the scrim.
 *
 * [defaultWidth] caps the content at the width a dialog takes on a window this size,
 * as a Compose dialog does by default. A dialog that sets its own width passes false.
 * [scrim] and [enter] are the dim behind it and how the content arrives, for a
 * dialog that has its own: the scrim fades in either way.
 *
 * [paneTitle] is what a screen reader announces as the dialog opens, its title in
 * words. [returnFocus] is where the keyboard goes when it closes, for an opener that
 * had it. Content that wants the keyboard in a field asks for it from inside
 * [content]: the dialog joins the window a frame after the call, so a request made
 * beside the call finds nothing to focus yet.
 *
 * Shown while it is in the composition, like the dialog it replaces.
 */
@Composable
fun NxDialog(
    onDismissRequest: (() -> Unit)?,
    dismissOnOutsidePress: Boolean = true,
    defaultWidth: Boolean = true,
    scrim: Color = DIALOG_SCRIM,
    enter: EnterTransition = fadeIn(Motion.tap.of()) + scaleIn(Motion.tap.of(), initialScale = 0.95f),
    paneTitle: String? = null,
    returnFocus: FocusRequester? = null,
    content: @Composable () -> Unit,
) {
    val pane = paneTitle
    val arrival = remember { MutableTransitionState(false) }
    arrival.targetState = true
    Layer(
        visible               = true,
        modal                 = true,
        onDismissRequest      = onDismissRequest,
        dismissOnOutsidePress = dismissOnOutsidePress,
        returnFocus           = returnFocus,
    ) {
        AnimatedVisibility(visibleState = arrival, enter = fadeIn(Motion.fade.of()), modifier = Modifier.matchParentSize()) {
            Box(Modifier.fillMaxSize().background(scrim))
        }
        Box(
            Modifier.matchParentSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(visibleState = arrival, enter = enter) {
                DialogWidth(defaultWidth) {
                    Box(
                        Modifier.layerBody().semantics {
                            dialog()
                            if (pane != null) this.paneTitle = pane
                        },
                    ) { content() }
                }
            }
        }
    }
}

/**
 * Measures [content] against the room the window leaves it, narrowed to the default
 * dialog width when [capped]: 580 on a window at least 600 across its shorter side,
 * 440 from 480, 320 below that.
 */
@Composable
private fun DialogWidth(capped: Boolean, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val bounded = if (capped && loose.hasBoundedWidth) {
            val shorter = minOf(loose.maxWidth, loose.maxHeight).toDp()
            val cap = when {
                shorter >= 600.dp -> 580.dp
                shorter >= 480.dp -> 440.dp
                else -> 320.dp
            }.roundToPx()
            loose.copy(maxWidth = minOf(cap, loose.maxWidth))
        } else {
            loose
        }
        val placeables = measurables.map { it.measure(bounded) }
        val width = placeables.maxOfOrNull { it.width } ?: 0
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }
}

/**
 * The question dialog: a title, a sentence and its answers, laid out the way
 * Material's `AlertDialog` lays them out, on an [NxDialog].
 *
 * Same parameters and the same look as the one it replaces, so a call site moves by
 * its name alone: the answers trail at the end, [dismissButton] before
 * [confirmButton], and wrap under each other when the row will not hold them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NxAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    dismissOnOutsidePress: Boolean = true,
    /** What a screen reader announces as the dialog opens, see [NxDialog]. */
    paneTitle: String? = null,
) {
    NxDialog(onDismissRequest = onDismissRequest, dismissOnOutsidePress = dismissOnOutsidePress, paneTitle = paneTitle) {
        Surface(
            modifier       = modifier.sizeIn(minWidth = ALERT_MIN_WIDTH, maxWidth = ALERT_MAX_WIDTH),
            shape          = shape,
            color          = containerColor,
            tonalElevation = tonalElevation,
        ) {
            Column(Modifier.padding(ALERT_PADDING)) {
                icon?.let {
                    CompositionLocalProvider(LocalContentColor provides iconContentColor) {
                        Box(Modifier.padding(bottom = 16.dp).align(Alignment.CenterHorizontally)) { it() }
                    }
                }
                title?.let {
                    ContentStyle(titleContentColor, MaterialTheme.typography.headlineSmall) {
                        Box(
                            Modifier
                                .padding(bottom = 16.dp)
                                .align(if (icon == null) Alignment.Start else Alignment.CenterHorizontally),
                        ) { it() }
                    }
                }
                text?.let {
                    ContentStyle(textContentColor, MaterialTheme.typography.bodyMedium) {
                        Box(Modifier.weight(1f, fill = false).padding(bottom = 24.dp).align(Alignment.Start)) { it() }
                    }
                }
                Box(Modifier.align(Alignment.End)) {
                    ContentStyle(MaterialTheme.colorScheme.primary, MaterialTheme.typography.labelLarge) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement   = Arrangement.spacedBy(12.dp),
                        ) {
                            dismissButton?.invoke()
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentStyle(colour: Color, style: TextStyle, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalContentColor provides colour,
        LocalTextStyle provides LocalTextStyle.current.merge(style),
        content = content,
    )
}

/** What a Compose dialog dims the window with by default. */
private val DIALOG_SCRIM = Color.Black.copy(alpha = 0.6f)

private val ALERT_MIN_WIDTH = 280.dp
private val ALERT_MAX_WIDTH = 560.dp
private val ALERT_PADDING = 24.dp

@Preview
@Composable
private fun NxDialogDarkPreview() = Dialogs(dark = true)

@Preview
@Composable
private fun NxDialogLightPreview() = Dialogs(dark = false)

/**
 * Each dialog in a window of its own, a [LayerHost] that draws its layers in place, so the
 * dialog, its scrim and the page under it are one picture. A dialog fades in as it opens, so
 * a static frame of the IDE may catch the start of that and the off-screen test the end of it.
 */
@Composable
private fun Dialogs(dark: Boolean) {
    NxTheme(NeutralTheme, dark = dark) {
        Row(
            modifier              = Modifier.width(1000.dp).background(NxColor.page).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LayerHost(rememberWindowLayers(), Modifier.size(476.dp, 340.dp)) {
                DialogPageBehind()
                NxAlertDialog(
                    onDismissRequest = {},
                    confirmButton    = { NxButton("Delete", onClick = {}, style = NxButtonStyle.Destructive) },
                    dismissButton    = { NxButton("Cancel", onClick = {}, style = NxButtonStyle.Tertiary) },
                    icon             = { Symbol(NxIcon.Delete, null) },
                    title            = { Text("Delete this pack?") },
                    text             = { Text("Its worlds and settings go with it.") },
                )
            }
            LayerHost(rememberWindowLayers(), Modifier.size(476.dp, 340.dp)) {
                DialogPageBehind()
                NxDialog(onDismissRequest = null) {
                    NxSurface(SurfaceKind.Dialog, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("A dialog with its own body", style = MaterialTheme.typography.titleMedium, color = NxInk.main)
                            Text("Only its own buttons close it.", style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
                            NxButton("Done", onClick = {}, modifier = Modifier.align(Alignment.End))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogPageBehind() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NxSectionHeader("Page behind")
        repeat(6) { Text("A line of the page the dialog covers", color = NxInk.main) }
    }
}
