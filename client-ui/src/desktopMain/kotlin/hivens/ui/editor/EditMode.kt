package hivens.ui.editor

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.widget.model.SurfaceId

// Edit mode is bound to one surface at a time -- the surface the
// EditorSurfaceHost FAB was on when the user toggled. Off when the
// host isn't on an editable surface or the user hasn't toggled.
//
// Static composition local: flips on whole-screen events (user
// toggles, navigates away from surface), not per-frame. Matches
// the choice for LocalLayoutGraph and LocalWidgetRegistry.
sealed class EditModeState {
    object Off : EditModeState()
    data class On(
        val surface: SurfaceId,
        val controller: EditModeController,
    ) : EditModeState()
}

val LocalEditMode: ProvidableCompositionLocal<EditModeState> =
    staticCompositionLocalOf { EditModeState.Off }
