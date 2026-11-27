package hivens.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import hivens.ui.layout.DocumentStore
import hivens.ui.widgets.ChildLevel
import hivens.widget.model.CompositionName
import hivens.widget.model.ConfigId
import hivens.widget.model.ScopeLevel
import org.koin.compose.koinInject

/**
 * A composition of the document other than the launcher's, drawn whole: its level and the config the document starts
 * it on. For a window the composition is shown in, or for what a host with one window shows in its place. Pages, where
 * it has any, are its pages host's.
 */
@Composable
fun CompositionContent(name: CompositionName, start: ConfigId) {
    val store: DocumentStore = koinInject()
    val document by store.document.collectAsState()
    ChildLevel(ScopeLevel.Composition) {
        ProvideCompositionFacts(name, document)
        val config = document.compositions[name]?.start ?: start
        ProvideComposition(config) {
            ConfigView(name, document, config, Modifier.fillMaxSize())
        }
    }
}
