package hivens.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.theme.LocalStyle
import hivens.widget.model.WidgetChrome

/**
 * The per-instance backing the kernel paints around a widget: inner padding, a
 * corner, and a glass card.
 *
 * Padding is an OUTER inset applied before the backing, so the rounded glass hugs
 * the widget's own view rather than the padded footprint. That is what lets the
 * right panel be inset from the window edge without the rounding detaching onto
 * the padded box.
 *
 * The corner follows the active style unless the instance names one, and the clip
 * is unconditional. It used to be skipped whenever the corner was zero, and zero
 * was the default, so raising the glass on a widget that had rounded itself put a
 * hard square behind it. Nobody chose that square; it was what "unset" looked like.
 *
 * Named and separate from [hivens.ui.AppShell] so a render test can see it. As an
 * anonymous lambda inside the shell it was unreachable from anything but a running
 * app, which is why the square survived as long as it did.
 */
@Composable
fun WidgetBacking(chrome: WidgetChrome, content: @Composable () -> Unit) {
    val glass = glassSurfaceAlpha(chrome.glassAlphaPct / 100f)
    val corner = chrome.explicitCornerDp?.dp ?: LocalStyle.current.cardCorner
    Box(
        Modifier
            .padding(
                PaddingValues(
                    start  = chrome.effectiveStart.dp,
                    top    = chrome.effectiveTop.dp,
                    end    = chrome.effectiveEnd.dp,
                    bottom = chrome.effectiveBottom.dp,
                ),
            )
            .clip(RoundedCornerShape(corner))
            .background(glass),
    ) { content() }
}
