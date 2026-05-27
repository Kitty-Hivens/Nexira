package hivens.ui.editor

import hivens.core.data.SessionData
import hivens.ui.AppState
import hivens.ui.Screen
import hivens.ui.widgets.home.classic.HomeClassicContext
import hivens.ui.widgets.home.new.HomeNewContext
import hivens.ui.widgets.library.LibraryContext
import hivens.ui.widgets.shell.LeftRailContext
import hivens.ui.widgets.shell.RightRailContext

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

internal val STUB_HOME_CLASSIC = HomeClassicContext(
    session               = SessionData(),
    initialSelectedServer = null,
    onServerSelected      = {},
    onSessionUpdated      = {},
    onCloseApp            = {},
    onOpenServerSettings  = {},
    onOpenDetails         = {},
)

internal val STUB_HOME_NEW = HomeNewContext(
    appState         = AppState.Loading,
    onScreenChange   = {},
    onSessionUpdated = {},
)

internal val STUB_LIBRARY = LibraryContext(
    appState       = AppState.Loading,
    onScreenChange = {},
)

internal val STUB_LEFTRAIL = LeftRailContext(
    currentScreen   = Screen.Home,
    isAuthenticated = false,
    onScreenChange  = {},
    onLogout        = {},
)

internal val STUB_RIGHTRAIL = RightRailContext(
    appState  = AppState.Loading,
    onLogin   = {},
    onLogout  = {},
    sslBypass = false,
)
