package hivens.ui.chrome

/**
 * Whether the caption buttons (minimize / maximize / close) should show. By
 * default they show wherever the window manager leaves anything to the window's
 * own frame (see [WindowCapabilities]), and stay hidden where it keeps all of it
 * to itself, since an in-app copy there is clutter. [WindowControlsMode] lets the
 * user force either way. Forced on, each button still needs the window manager to
 * allow its action, except close, which is always the app's own.
 */
@kotlinx.serialization.Serializable
enum class WindowControlsMode { Auto, Show, Hide }

fun WindowControlsMode.resolved(capabilities: WindowCapabilities): Boolean = when (this) {
    WindowControlsMode.Show -> true
    WindowControlsMode.Hide -> false
    WindowControlsMode.Auto -> capabilities.any
}
