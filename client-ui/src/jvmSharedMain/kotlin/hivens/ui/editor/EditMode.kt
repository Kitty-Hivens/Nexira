package hivens.ui.editor

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

// Whether somebody is arranging the window, and the editor they arrange it with.
//
// Static composition local: flips on whole-window events (somebody enters or
// leaves the editor), not per frame.
sealed class EditModeState {
    object Off : EditModeState()
    data class On(val editor: DocumentEditor) : EditModeState()
}

val LocalEditMode: ProvidableCompositionLocal<EditModeState> =
    staticCompositionLocalOf { EditModeState.Off }
