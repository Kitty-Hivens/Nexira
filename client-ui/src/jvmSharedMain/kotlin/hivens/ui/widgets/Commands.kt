package hivens.ui.widgets

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
 * Every key listed is registered, made by a factory or declared on every host, except
 * the ones that say which host answers them, such as [CheckUpdate]. A key nothing
 * answers is missing where it is read, which keeps a widget that needs it off the
 * surface, and fired anyway it does nothing. A key need not have a consumer in the
 * launcher's own widgets: these are what any widget, a module's among them, can
 * fire, so a key is listed for what the launcher can do, not for who uses it today.
 *
 * A command below the app level is fired through `rememberScopedCommand(...)`. Most
 * are made by each level of their kind from the registry's factory, so they are there
 * wherever such a level is, whoever draws it. The history's are provided by the
 * composition's owner, so a window moves its own history.
 *
 * The pack commands are the packs module's, in `hivens.packs.api.PackKeys`, the
 * sign-in and sign-out commands the accounts module's, in `hivens.accounts.api.AccountKeys`,
 * and choosing a theme and what decides dark and light the themes module's, in
 * `hivens.themes.api.ThemesKeys`.
 */
object Commands {
    val ClearNotifications = CommandKey<Unit>("notifications.clear")

    /**
     * Checks for a newer build of the launcher. Answered only by a host that can
     * replace the launcher itself, the desktop. Absent on a phone, whose launcher is
     * updated through its package, so a widget that needs it is kept off the surface
     * there rather than drawn with a button that does nothing.
     */
    val CheckUpdate = CommandKey<Unit>("update.check")
    // Sets the "do not disturb" popup mute; paired with [Sources.DoNotDisturb].
    val SetDoNotDisturb = CommandKey<Boolean>("notifications.dnd.set")

    /** Goes to a screen, keeping the way back. */
    val Navigate = CommandKey<Screen>("nav.open", ScopeLevel.Composition)

    /**
     * Goes to a screen as a fresh start, the way a rail entry does: the history
     * becomes the composition's home and that screen, so back from it comes home.
     */
    val SwitchTab = CommandKey<Screen>("nav.switch", ScopeLevel.Composition)

    /**
     * Steps back one screen. At the root it moves nothing and is counted in
     * [Sources.Navigation], for a composition that answers a back with nowhere to go.
     */
    val Back = CommandKey<Unit>("nav.back", ScopeLevel.Composition)
    val Forward = CommandKey<Unit>("nav.forward", ScopeLevel.Composition)

    /** Steps back to a screen already in the trail. */
    val PopTo = CommandKey<Screen>("nav.popTo", ScopeLevel.Composition)

    /** Changes the wallpaper settings by one change applied to what they are when it lands. */
    val UpdateBackground = CommandKey<BackgroundSettings.() -> BackgroundSettings>("background.update")

    /** Puts the console on screen or takes it off. Paired with [Sources.ConsoleVisible]. */
    val SetConsoleVisible = CommandKey<Boolean>("console.visible.set")

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
