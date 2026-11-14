package hivens.ui.widgets

import hivens.core.activity.Activity
import hivens.core.data.PackInstance
import hivens.core.data.ThemeMode
import hivens.core.launch.LaunchState
import hivens.launcher.InstallSnapshot
import hivens.ui.AppState
import hivens.ui.background.BackgroundSettings
import hivens.ui.i18n.AppLocale
import hivens.ui.navigation.NavState
import hivens.ui.notifications.IndicationCenter.LaunchIndication
import hivens.ui.notifications.PersistedNotification
import hivens.ui.feature.catalogue.project.OpenProject
import hivens.ui.host.HostWindowState
import hivens.ui.theme.ThemeLibrary
import hivens.ui.widgets.about.SystemFacts
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey

/**
 * Catalog of reactive data sources widgets bind to via `rememberSource(...)`.
 * Lives in client-ui because only this module can name the source value types
 * (Activity is client-core, PersistedNotification is client-ui); the low-level
 * WidgetDataRegistry keys on the string id alone, so a rule-engine / editor
 * references a source without seeing these types.
 *
 * A key below the app level is read with `rememberScopedSource(...)`. Most are made
 * by each level of their kind from a factory the registry holds, out of the level's
 * services, so they exist wherever such a level does, whoever draws it. The few only
 * a level's owner has, its history and its window, are declared in the registry so
 * the editor knows where they live, and the owner provides them.
 */
object Sources {
    /**
     * Everything the launcher is currently doing, as the activity registry has
     * it: installs, pack updates, per-file content updates.
     *
     * One feed rather than one per service, because a widget showing "what is
     * happening" has no business knowing which subsystem is behind it -- that is
     * the registry's whole reason to exist.
     */
    val Activity = SourceKey<List<Activity>>("activity")
    val Notifications = SourceKey<List<PersistedNotification>>("notifications.archive")
    // "Do not disturb" live state -- the notification-history widget reflects it
    // on its mute toggle; NotificationStack reads the same flow to gate popups.
    val DoNotDisturb = SourceKey<Boolean>("notifications.dnd")
    // The project whose page is open in this window, or null anywhere else. Read by
    // the right rail's project-view family, which is shown on that page and has
    // nothing to say off it. Per window, so a second window's rail describes its
    // own page.
    val OpenProject = SourceKey<OpenProject?>("mod.open", ScopeLevel.Window)

    /**
     * The window a widget is drawn in, as the host reads it: its size, whether it
     * is maximised, minimised and focused, and what the platform allows. Changed
     * through the window commands in [Commands], never directly.
     */
    val Window = SourceKey<HostWindowState>("window.state", ScopeLevel.Window)

    /** Where the composition's history stands. Moved by the navigation commands in [Commands]. */
    val Navigation = SourceKey<NavState>("nav.state", ScopeLevel.Composition)

    /**
     * Who the launcher is signed in as, that nobody is, or that startup is still
     * finding out. The app's, since the accounts are. Moved by [Commands.SignIn] and
     * [Commands.SignOut].
     */
    val Session = SourceKey<AppState>("app.state")

    /** Whether the user let the launcher reach its own server past a certificate it does not trust. */
    val SslBypass = SourceKey<Boolean>("ssl.bypass")

    /** The wallpaper and what is done to it. Changed through [Commands.UpdateBackground]. */
    val Background = SourceKey<BackgroundSettings>("background.settings")

    /** The machine's memory and processor, read once a run. */
    val SystemInfo = SourceKey<SystemFacts>("system.info")

    /**
     * Moves whenever an account is signed in or out. The account store has no
     * reactive seam of its own, and removing an account that was not fronting the
     * shell leaves the session as it was, so a reader keyed on the session alone
     * went on offering an account that was gone. Moved by [Commands.AccountsChanged].
     */
    val AccountsRevision = SourceKey<Int>("accounts.revision")

    /** Every theme there is and the one in use, as the composition draws with them. Changed through [Commands.ApplyTheme]. */
    val ThemeLibrary = SourceKey<ThemeLibrary>("theme.library", ScopeLevel.Composition)

    /** Whether the composition is drawn in its dark scheme. Set by [Commands.SetDarkTheme]. */
    val DarkTheme = SourceKey<Boolean>("theme.dark", ScopeLevel.Composition)

    /** What decides [DarkTheme]: the person, the system's scheme or the wallpaper. Changed through [Commands.SetThemeMode]. */
    val ThemeMode = SourceKey<ThemeMode>("theme.mode", ScopeLevel.Composition)

    /** Whether the host can read the system's colour scheme, so following it can be offered. A fact of the host, so the app's. */
    val SystemSchemeAvailable = SourceKey<Boolean>("theme.system.available")

    /** The language the composition speaks. Changed through [Commands.SetLocale]. */
    val Locale = SourceKey<AppLocale>("locale", ScopeLevel.Composition)

    /** Every pack installed, in the order the store keeps them. */
    val PackLibrary = SourceKey<List<PackInstance>>("packs.library")

    /**
     * Every pack with a launch in flight and where it stands, by pack id. A pack with
     * nothing in flight is absent. Moved by [Commands.LaunchPack] and [Commands.StopPack].
     */
    val PackLaunches = SourceKey<Map<String, LaunchIndication>>("packs.launches")

    /** What the one launch the launcher runs at a time is doing, from preparing to the game's exit. */
    val LaunchState = SourceKey<LaunchState>("launch.state")

    /**
     * Every pack install there is, running or finished and not yet dismissed, by its
     * key. Started by [Commands.InstallPack].
     */
    val PackInstalls = SourceKey<Map<String, InstallSnapshot>>("packs.installs")

    /** Whether the console is asked to be on screen. Changed through [Commands.SetConsoleVisible]. */
    val ConsoleVisible = SourceKey<Boolean>("console.visible")
}
