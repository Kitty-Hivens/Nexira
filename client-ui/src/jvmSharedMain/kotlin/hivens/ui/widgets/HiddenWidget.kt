package hivens.ui.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.nexiraStrings
import hivens.widget.api.HiddenWidgetDecorator

/**
 * What the editor draws for a widget whose show condition does not hold where it stands, which
 * the slot leaves out for anybody using the launcher: the widget itself, dimmed like one on
 * samples, under a mark naming the source it waits for, so whoever is arranging sees what it is
 * and why nobody else does. The id is shown as it is, the way a misplaced widget shows what it
 * misses: it names a key and is not a word to translate.
 *
 * A widget with nothing to show here still gets the mark, which then stands alone in its place.
 */
val HiddenWidget: HiddenWidgetDecorator = { descriptor, _, content ->
    Box {
        Box(Modifier.alpha(SAMPLE_ALPHA)) { content() }
        DimMark(nexiraStrings().text("editorHiddenHere", descriptor.shownWhen.orEmpty()), Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}
