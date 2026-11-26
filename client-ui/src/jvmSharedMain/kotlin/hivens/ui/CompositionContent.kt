package hivens.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import hivens.ui.layout.DocumentStore
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
 * in its place. It shows the configs the document gives it.
 */
@Composable
fun CompositionContent(name: CompositionName, start: ConfigId) {
    val histories: CompositionHistories = koinInject()
    val store: DocumentStore = koinInject()
    val document by store.document.collectAsState()
    val history = remember(histories, name) { histories.of(name, start) }
    val has by rememberUpdatedState { config: ConfigId -> document.compositions[name]?.configs?.containsKey(config) == true }
    val show: (Show) -> Unit = remember(history) { { if (has(it.config)) history.show(it) } }
    ChildLevel(ScopeLevel.Composition) {
        ProvideComposition(history, show) {
            ConfigView(name, document, history.now, Modifier.fillMaxSize())
        }
    }
}
