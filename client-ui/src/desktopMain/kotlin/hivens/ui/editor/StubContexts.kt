package hivens.ui.editor

import hivens.ui.AppState
import hivens.ui.Screen
import hivens.ui.widgets.home.new.HomeNewContext
import hivens.ui.widgets.library.LibraryContext
import hivens.ui.widgets.shell.LeftRailContext
import hivens.ui.widgets.shell.RightRailContext
import hivens.ui.widgets.shell.ShellContext

// No-op stubs for every per-surface context. EditorSurfaceHost provides
// them at its level BELOW the active surface composable. The active
// surface still overrides with the real context (CompositionLocalProvider
// child shadows parent), so widgets in their natural slot see real data.
//
// A widget dragged onto a foreign surface (e.g. a home.new widget
// dropped into home.classic.main via the palette) falls through to the
// stub instead of throwing. The widget renders; its navigation
// callbacks are no-ops. Cosmetic, but the launcher stays alive while
// the user explores the editor.
//
// Phase 5 widget capability metadata + palette filtering will narrow
// the palette to compatible widgets per surface so foreign-drop never
// happens in the first place; until then the stubs are the safety net.

internal val STUB_HOME_NEW = HomeNewContext(
    appState         = AppState.Loading,
    onScreenChange   = {},
)

internal val STUB_LIBRARY = LibraryContext(
    appState       = AppState.Loading,
    onScreenChange = {},
)

internal val STUB_LEFTRAIL = LeftRailContext(
    currentScreen   = Screen.Home,
    isAuthenticated = false,
    onScreenChange  = {},
    onSwitchTab     = {},
    onLogout        = {},
)

internal val STUB_RIGHTRAIL = RightRailContext(
    appState  = AppState.Loading,
    onLogin   = {},
    onLogout  = {},
    sslBypass = false,
)

// The shell frame's context, read by the breadcrumb and the three region widgets.
// It is the one error()-defaulted surface local with no stub, so those widgets
// were the only ones that threw (and flooded the console) when the palette drew
// their off-surface preview. centerBody draws nothing here: a preview is not the
// shell and has no screen to route.
internal val STUB_SHELL = ShellContext(
    currentScreen   = Screen.Home,
    isAuthenticated = false,
    onScreenChange  = {},
    onSwitchTab     = {},
    onLogout        = {},
    appState        = AppState.Loading,
    onLogin         = {},
    sslBypass       = false,
    centerBody      = {},
    trail           = emptyList(),
    canGoBack       = false,
    canGoForward    = false,
    onBack          = {},
    onForward       = {},
    onPopTo         = {},
)
