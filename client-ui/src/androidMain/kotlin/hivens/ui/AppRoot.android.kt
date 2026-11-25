package hivens.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import coil3.ComponentRegistry
import hivens.ui.background.BackgroundSettings
import hivens.ui.background.CustomBackground
import hivens.ui.background.rememberDeviceTilt
import hivens.themes.api.WallpaperTone
import hivens.ui.host.CompositionWindows
import hivens.ui.layers.Layer
import hivens.ui.navigation.CompositionHistory
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.widget.model.Document

/**
 * The system's back, for as long as the composition draws: one step back in the
 * history, or at the root a back counted for the composition to answer. Always
 * enabled, so a back at the root reaches the composition instead of falling through
 * to the system, which would take the person out of the launcher. The shell's
 * dismiss stack registers after this one and is asked first.
 */
@Composable
internal actual fun NavigationInputs(history: CompositionHistory) {
    BackHandler(enabled = true) { history.back() }
}

@Composable
internal actual fun WallpaperLayer(
    settings: BackgroundSettings,
    mousePosProvider: () -> Offset,
    onTone: (WallpaperTone) -> Unit,
    onAudioVolume: (Float) -> Unit,
) {
    val tilt = rememberDeviceTilt(enabled = settings.parallaxIntensity > 0f)
    CustomBackground(
        settings         = settings,
        mousePosProvider = {
            val touch = mousePosProvider()
            val shift = tilt()
            Offset((touch.x + shift.x).coerceIn(0f, 1f), (touch.y + shift.y).coerceIn(0f, 1f))
        },
        onTone           = onTone,
        onAudioVolume    = onAudioVolume,
    )
}

/** A phone has one window, so a window a composition owns is shown over it as a modal layer. */
@Composable
internal actual fun OwnedWindows(document: Document, windows: CompositionWindows) {
    val open by windows.open.collectAsState()
    document.compositions.forEach { (name, composition) ->
        key(name) {
            Layer(visible = name in open, modal = true, onDismissRequest = { windows.close(name) }, dismissOnOutsidePress = false) {
                NxSurface(SurfaceKind.Page, Modifier.fillMaxSize()) { CompositionContent(name, composition.start) }
            }
        }
    }
}

internal actual fun ComponentRegistry.Builder.addPlatformDecoders() = Unit
