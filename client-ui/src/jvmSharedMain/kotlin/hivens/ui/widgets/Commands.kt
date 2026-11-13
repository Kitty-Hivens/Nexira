package hivens.ui.widgets

import hivens.core.data.SessionData
import hivens.ui.Screen
import hivens.ui.background.BackgroundSettings
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel

/**
 * Catalog of commands widgets fire via `rememberCommand(...)` / `rememberAction(...)`
 * -- the write counterpart of [Sources]. Lives in client-ui for the same reason:
 * the backing services are named here, while the WidgetCommandRegistry keys on the
 * string id alone.
 *
 * Only commands with a live consumer (or a deliberately-seamed one) are listed: a
 * declared key with no registration would throw on dispatch. Music / console / the
 * launch path enter as their widgets migrate off direct injection.
 *
 * A command below the app level is fired through `rememberScopedCommand(...)`, and
 * each composition's owner provides its own, so a window moves its own history.
 */
object Commands {
    val ClearNotifications = CommandKey<Unit>("notifications.clear")
    val CheckUpdate = CommandKey<Unit>("update.check")
    // Sets the "do not disturb" popup mute; paired with [Sources.DoNotDisturb].
    val SetDoNotDisturb = CommandKey<Boolean>("notifications.dnd.set")

    /** Goes to a screen, keeping the way back. */
    val Navigate = CommandKey<Screen>("nav.open", ScopeLevel.Composition)

    /** Goes to a screen as a fresh start, the way a rail entry does: the history is dropped. */
    val SwitchTab = CommandKey<Screen>("nav.switch", ScopeLevel.Composition)

    val Back = CommandKey<Unit>("nav.back", ScopeLevel.Composition)
    val Forward = CommandKey<Unit>("nav.forward", ScopeLevel.Composition)

    /** Steps back to a screen already in the trail. */
    val PopTo = CommandKey<Screen>("nav.popTo", ScopeLevel.Composition)

    /** Makes a session the one the composition is signed in as. */
    val SignIn = CommandKey<SessionData>("auth.signIn", ScopeLevel.Composition)

    /** Asks to sign out of everything, which the composition confirms first. */
    val SignOut = CommandKey<Unit>("auth.signOut", ScopeLevel.Composition)

    /** Changes the wallpaper settings by one change applied to what they are when it lands. */
    val UpdateBackground = CommandKey<BackgroundSettings.() -> BackgroundSettings>("background.update")

    /** Says an account was signed in or out. Paired with [Sources.AccountsRevision]. */
    val AccountsChanged = CommandKey<Unit>("accounts.changed")

    /** Puts the theme of this id in use and leaves the picker. */
    val ApplyTheme = CommandKey<String>("theme.apply", ScopeLevel.Composition)

    // What a widget may ask of the window it is drawn in, each offered only by a
    // window that takes it. One the window manager does not allow at the moment
    // does nothing, the way the caption buttons stand down. Paired with [Sources.Window].
    val MaximizeWindow = CommandKey<Unit>("window.maximize", ScopeLevel.Window)
    val MinimizeWindow = CommandKey<Unit>("window.minimize", ScopeLevel.Window)

    /** Brings a minimised window back and a maximised one to its normal size. */
    val RestoreWindow = CommandKey<Unit>("window.restore", ScopeLevel.Window)

    /** Closes the window the way its own close button does: the main window may hide to the tray. */
    val CloseWindow = CommandKey<Unit>("window.close", ScopeLevel.Window)
}
