package hivens.ui.widgets

import hivens.core.activity.Activity
import hivens.ui.AppState
import hivens.ui.background.BackgroundSettings
import hivens.ui.navigation.NavState
import hivens.ui.notifications.PersistedNotification
import hivens.ui.feature.catalogue.project.OpenProject
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
 * A key below the app level is read with `rememberScopedSource(...)`. The ones a
 * composition's owner provides are declared in the registry so the editor knows
 * where they live, and the shell's composition provides them all.
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
    // The project whose page is open, or null anywhere else in the app. Read by
    // the right rail's project-view family, which is shown on that page and has
    // nothing to say off it.
    val OpenProject = SourceKey<OpenProject?>("mod.open")

    /** Where the composition's history stands. Moved by the navigation commands in [Commands]. */
    val Navigation = SourceKey<NavState>("nav.state", ScopeLevel.Composition)

    /** Who the composition is signed in as, that nobody is, or that startup is still finding out. */
    val Session = SourceKey<AppState>("app.state", ScopeLevel.Composition)

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

    /** Every theme there is and the one in use, as the composition draws with them. */
    val ThemeLibrary = SourceKey<ThemeLibrary>("theme.library", ScopeLevel.Composition)

    /** Whether the composition is drawn in its dark scheme. */
    val DarkTheme = SourceKey<Boolean>("theme.dark", ScopeLevel.Composition)
}
