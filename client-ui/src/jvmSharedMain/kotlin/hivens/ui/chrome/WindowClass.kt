package hivens.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much room the window gives the layout, in the three steps the shell and the screens
 * decide on. The desktop never leaves [Expanded]: its window cannot be made narrower than the
 * design width.
 */
enum class WindowClass {
    Compact,
    Medium,
    Expanded;

    companion object {
        fun of(width: Dp): WindowClass = when {
            width < 600.dp -> Compact
            width < 840.dp -> Medium
            else -> Expanded
        }
    }
}

val LocalWindowClass = compositionLocalOf { WindowClass.Expanded }

@Composable
fun rememberWindowClass(): WindowClass {
    val density = LocalDensity.current
    val widthPx = LocalWindowInfo.current.containerSize.width
    return WindowClass.of(with(density) { widthPx.toDp() })
}

/**
 * Whether the host opens the right rail as a sheet when the window is too narrow to hold it in
 * place. Off where a keyboard chord can reopen a collapsed rail, which is the desktop.
 */
val LocalRightRailSheet = compositionLocalOf { false }
