package hivens.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import coil3.ComponentRegistry
import hivens.ui.background.BackgroundSettings
import hivens.ui.background.CustomBackground
import hivens.ui.background.rememberDeviceTilt
import hivens.ui.theme.WallpaperTone

/**
 * The system's back, for as long as the composition draws: one step back in the
 * history, or at the root a back counted for the composition to answer. Always
 * enabled, so a back at the root reaches the composition instead of falling through
 * to the system, which would take the person out of the launcher. The shell's
 * dismiss stack registers after this one and is asked first.
 */
@Composable
internal actual fun NavigationInputs(backStack: NavBackStack) {
    BackHandler(enabled = true) { backStack.back() }
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

internal actual fun ComponentRegistry.Builder.addPlatformDecoders() = Unit
