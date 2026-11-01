package hivens.ui.widgets.library

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.ui.AppState
import hivens.ui.Screen

// Navigation context for the library surface. Provided by
// LibraryScreen, consumed by library.header / library.body widgets.
data class LibraryContext(
    val appState: AppState,
    val onScreenChange: (Screen) -> Unit,
)

val LocalLibraryContext: ProvidableCompositionLocal<LibraryContext> =
    staticCompositionLocalOf {
        error("LocalLibraryContext not provided -- mount inside LibraryScreen")
    }
