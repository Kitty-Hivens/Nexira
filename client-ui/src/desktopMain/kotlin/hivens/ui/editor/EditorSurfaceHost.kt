package hivens.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.core.data.HomeView
import hivens.ui.Screen
import hivens.ui.theme.CelestiaTheme
import hivens.widget.model.SurfaceId
import org.koin.compose.koinInject

// Hosts the edit-mode FAB over the active editable surface. Resolves
// the surface from (Screen, HomeView) and provides LocalEditMode to
// the wrapped content. editor-1 ships the FAB + state toggle only;
// decoration overlays + DnD land in editor-2.
@Composable
fun EditorSurfaceHost(
    currentScreen: Screen,
    homeView: HomeView,
    content: @Composable () -> Unit,
) {
    val editableSurface: SurfaceId? = remember(currentScreen, homeView) {
        editableSurfaceFor(currentScreen, homeView)
    }

    val controller: EditModeController = koinInject()
    var editing by remember(editableSurface) { mutableStateOf(false) }
    // Leaving a surface drops edit mode -- avoids the case where the
    // user toggles edit on Home, navigates to Settings, returns, and
    // sees a stale "edit" state with a target surface that no longer
    // matches what they remembered.
    val state: EditModeState = remember(editing, editableSurface) {
        if (editing && editableSurface != null) EditModeState.On(editableSurface, controller)
        else EditModeState.Off
    }

    CompositionLocalProvider(LocalEditMode provides state) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (editableSurface != null) {
                FloatingActionButton(
                    onClick           = { editing = !editing },
                    modifier          = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                    containerColor    = if (editing) CelestiaTheme.colors.primary
                                        else CelestiaTheme.colors.surfaceVariant,
                ) {
                    Icon(
                        imageVector        = if (editing) Icons.Default.Done else Icons.Default.Edit,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}

// Maps the active navigation target to the SurfaceId the editor should
// bind to. Returns null on screens that aren't widget-composed (Profile,
// Settings, etc.) so the FAB stays hidden there.
private fun editableSurfaceFor(screen: Screen, homeView: HomeView): SurfaceId? = when (screen) {
    Screen.Home -> when (homeView) {
        HomeView.Classic      -> SurfaceId("home.classic")
        HomeView.LibraryFirst -> SurfaceId("library")
        HomeView.New          -> SurfaceId("home.new")
    }
    Screen.Library -> SurfaceId("library")
    else           -> null
}
