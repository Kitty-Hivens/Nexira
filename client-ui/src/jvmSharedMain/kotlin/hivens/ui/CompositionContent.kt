package hivens.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import hivens.ui.layout.DocumentStore
import hivens.ui.navigation.asConfig
import hivens.ui.widgets.ChildLevel
import hivens.widget.model.CompositionName
import hivens.widget.model.ConfigId
import hivens.widget.model.ScopeLevel
import org.koin.compose.koinInject

/**
 * A composition of the document other than the launcher's, drawn whole: its level, its history as the history module
 * keeps it for the run, and the config the history shows. For a window the composition is shown in, or for what a host
 * with one window shows in its place. It shows the configs the document gives it.
 */
@Composable
fun CompositionContent(name: CompositionName, start: ConfigId) {
    val store: DocumentStore = koinInject()
    val document by store.document.collectAsState()
    ChildLevel(ScopeLevel.Composition) {
        ProvideCompositionFacts(name, document)
        val (history, _) = rememberCompositionHistory(start)
        ProvideComposition(history.current.screen.asConfig()) {
            ConfigView(name, document, history, Modifier.fillMaxSize())
        }
    }
}
