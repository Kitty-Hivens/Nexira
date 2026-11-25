package hivens.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import hivens.ui.navigation.CompositionHistories
import hivens.ui.widgets.ChildLevel
import hivens.widget.model.CompositionName
import hivens.widget.model.ConfigId
import hivens.widget.model.ScopeLevel
import hivens.widget.model.Show
import org.koin.compose.koinInject

/**
 * A composition of the document other than the launcher's, drawn whole: its level, its history kept for the run, and
 * the config the history shows. For a window the composition is shown in, or for what a host with one window shows
 * in its place.
 *
 * It shows [start] and nothing else, until what a composition holds is read from the document.
 */
@Composable
fun CompositionContent(name: CompositionName, start: ConfigId) {
    val histories: CompositionHistories = koinInject()
    val history = remember(histories, name) { histories.of(name, start) }
    val show: (Show) -> Unit = remember(history, start) { { if (it.config == start) history.show(it) } }
    ChildLevel(ScopeLevel.Composition) {
        ProvideComposition(history, show) {
            ScreenRouter(history.now) { entry -> ConfigBody(entry.config) }
        }
    }
}
