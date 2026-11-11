package hivens.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import coil3.ComponentRegistry
import hivens.ui.background.BackgroundSettings
import hivens.ui.background.CustomBackground
import hivens.ui.background.rememberDeviceTilt
import hivens.ui.theme.WallpaperTone

@Composable
internal actual fun NavigationInputs(backStack: NavBackStack) {
    BackHandler(enabled = backStack.canGoBack) { backStack.back() }
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
