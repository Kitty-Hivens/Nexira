package hivens.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * What the top bar needs of the window it sits in, when the app draws the window's
 * frame itself.
 *
 * A desktop window is undecorated, so the bar carries the caption buttons and is the
 * lane the window is dragged by. A phone, and a freeform window on a phone, has its
 * frame drawn by the system, so there is nothing to carry and no frame here.
 */
interface WindowFrame {
    /** Moves between maximised and restored. */
    fun toggleMaximize()

    /** The minimise, maximise and close buttons. */
    @Composable
    fun Controls()

    /** Makes the region this modifier is on drag the window, a double click toggling maximise. */
    fun Modifier.dragArea(): Modifier

    /**
     * Keeps clicks on this element inside a [dragArea] for the element, where the
     * system decides what a press on the window does before the app sees it.
     */
    fun Modifier.dragExclusion(): Modifier = this
}

/** The window's frame, or null where the system owns it. */
val LocalWindowFrame = staticCompositionLocalOf<WindowFrame?> { null }

/**
 * Whether the in-app top bar replaces the OS chrome (undecorated window). Mirrors
 * SettingsData.useCustomChrome, provided from AppShell. When false the OS title
 * bar is shown, so the bar's caption buttons / window-drag must stand down (the
 * OS handles minimize/maximize/close/move). The breadcrumb still renders -- it is
 * app content, not window chrome. Default true (the breadcrumb-only widget preview).
 */
val LocalUseCustomChrome = staticCompositionLocalOf { true }
