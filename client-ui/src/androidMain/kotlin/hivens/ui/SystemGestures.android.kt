package hivens.ui

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier

internal actual fun Modifier.systemGestureExcluded(): Modifier = systemGestureExclusion()
