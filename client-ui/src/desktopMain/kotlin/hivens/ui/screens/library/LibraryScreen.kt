package hivens.ui.screens.library

import androidx.compose.foundation.layout.Box
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
            Box(Modifier.fillMaxWidth()) {
                SlotRenderer(SurfaceId(SURFACE), SlotId("header"))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SlotRenderer(SurfaceId(SURFACE), SlotId("body"))
            }
        }
    }
}

private const val SURFACE = "library"
