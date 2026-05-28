package hivens.ui.screens.library
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.AppState
import hivens.ui.Screen
import hivens.ui.puppet.PuppetScreen
import hivens.ui.widgets.library.LibraryContext
import hivens.ui.widgets.library.LocalLibraryContext
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId

/**
 * Library = user's collection of installed packs. Surface composable:
 * owns the screen padding + header/body column; widget content
 * (header text, list-or-empty) resolves through SlotRenderer against
 * the layout graph.
 */
@Composable
fun LibraryScreen(
    appState: AppState,
    onScreenChange: (Screen) -> Unit,
) {
    PuppetScreen("Library")

    val ctx = remember(appState, onScreenChange) {
        LibraryContext(appState = appState, onScreenChange = onScreenChange)
    }
    CompositionLocalProvider(LocalLibraryContext provides ctx) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
            SlotRenderer(
                SurfaceId(SURFACE),
                SlotId("header"),
                modifier = Modifier.fillMaxWidth(),
                spacing  = 8.dp,
            )
            // No verticalScroll on the body slot. Wrapping a Lazy
            // widget (library.body LazyColumn, any future Lazy
            // user-dropped widget) in Column.verticalScroll hands the
            // child maxHeight = Infinity and Compose aborts measure.
            // library.body manages its own scroll via LazyColumn; the
            // slot distributes its bounded height among children.
            SlotRenderer(
                SurfaceId(SURFACE),
                SlotId("body"),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                spacing  = 8.dp,
            )
        }
    }
}

private const val SURFACE = "library"
