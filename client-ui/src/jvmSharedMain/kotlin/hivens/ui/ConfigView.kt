package hivens.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import hivens.widget.api.LocalDocument
import hivens.widget.api.SlotRenderer
import hivens.widget.model.CompositionName
import hivens.widget.model.ConfigId
import hivens.widget.model.Document
import hivens.widget.model.NodePath
import hivens.widget.model.NodeRoot

/**
 * The config [config] of the composition [name] in [document], in the layout that fits the area it is drawn in.
 * Nothing where the composition or the config is not in the document.
 *
 * A widget the composition has once stands as the same node wherever the config holds it. Which page shows is the
 * pages host's, a widget of the config like any other.
 */
@Composable
internal fun ConfigView(name: CompositionName, document: Document, config: ConfigId, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val composition = document.compositions[name] ?: return@BoxWithConstraints
        val layout = composition.configs[config]?.layoutFor(maxWidth.value, maxHeight.value)?.id ?: return@BoxWithConstraints
        CompositionLocalProvider(LocalDocument provides document) {
            SlotRenderer(NodePath(name, NodeRoot.Layout(config, layout)), Modifier.fillMaxSize())
        }
    }
}
