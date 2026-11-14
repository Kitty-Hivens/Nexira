package hivens.ui.widgets

import hivens.core.data.SessionData
import hivens.core.data.ThemeMode
import hivens.ui.Screen
import hivens.ui.background.BackgroundSettings
import hivens.ui.i18n.AppLocale
import hivens.ui.packs.PackInstallRequest
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel

/**
 * Catalog of commands widgets fire via `rememberCommand(...)` / `rememberAction(...)`
 * -- the write counterpart of [Sources]. Lives in client-ui for the same reason:
 * the backing services are named here, while the WidgetCommandRegistry keys on the
 * string id alone.
 *
 * Every key listed is registered, made by a factory or declared, since a key
 * nothing answers would throw on dispatch. A key need not have a consumer in the
 * launcher's own widgets: these are what any widget, a module's among them, can
 * fire, so a key is listed for what the launcher can do, not for who uses it today.
 *
 * A command below the app level is fired through `rememberScopedCommand(...)`. Most
 * are made by each level of their kind from the registry's factory, so they are there
 * wherever such a level is, whoever draws it. The history's are provided by the
 * composition's owner, so a window moves its own history.
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

    /** Makes a session the one the launcher is signed in as. */
    val SignIn = CommandKey<SessionData>("auth.signIn")

    /**
     * Signs out of everything: every stored account, the face choice and the
     * offline name. At once, so whatever offers it asks the person first.
     */
    val SignOut = CommandKey<Unit>("auth.signOut")

    /** Changes the wallpaper settings by one change applied to what they are when it lands. */
    val UpdateBackground = CommandKey<BackgroundSettings.() -> BackgroundSettings>("background.update")

    /** Says an account was signed in or out. Paired with [Sources.AccountsRevision]. */
    val AccountsChanged = CommandKey<Unit>("accounts.changed")

    /** Puts the theme of this id in use. */
    val ApplyTheme = CommandKey<String>("theme.apply", ScopeLevel.Composition)

    /** Draws dark or light, as the person's own choice, which overrides whatever [SetThemeMode] said. */
    val SetDarkTheme = CommandKey<Boolean>("theme.dark.set", ScopeLevel.Composition)

    /** Says what decides dark and light from now on. Paired with [Sources.ThemeMode]. */
    val SetThemeMode = CommandKey<ThemeMode>("theme.mode.set", ScopeLevel.Composition)

    /** Speaks this language from now on. Paired with [Sources.Locale]. */
    val SetLocale = CommandKey<AppLocale>("locale.set", ScopeLevel.Composition)

    /**
     * Starts the game of the pack with this id as the launcher's session, or as the
     * offline name when nobody is signed in. A pack that is not installed fails the
     * way any launch does, out loud. Paired with [Sources.PackLaunches].
     */
    val LaunchPack = CommandKey<String>("pack.launch")

    /** Asks the game of the pack with this id to end. */
    val StopPack = CommandKey<String>("pack.stop")

    /** Installs a build of a catalogue pack. Its progress is in [Sources.PackInstalls]. */
    val InstallPack = CommandKey<PackInstallRequest>("pack.install")

    /** Forgets the finished install with this key. */
    val DismissInstall = CommandKey<String>("pack.install.dismiss")

    /** Stops the running install with this key and removes what it wrote. */
    val CancelInstall = CommandKey<String>("pack.install.cancel")

    /** Moves the pack with this id to its latest build. */
    val UpdatePack = CommandKey<String>("pack.update")

    /** Deletes the pack with this id and its files. Refused while its game runs or other work holds it. */
    val DeletePack = CommandKey<String>("pack.delete")

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
