package hivens.ui.widgets

import hivens.core.activity.Activity
import hivens.ui.background.BackgroundSettings
import hivens.ui.notifications.PersistedNotification
import hivens.ui.host.HostWindowState
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
 * services, so they exist wherever such a level does, whoever draws it. What only
 * a level's owner has, its window, is declared in the registry so the editor knows
 * where it lives, and the owner provides it. A composition's history is the kernel's
 * key, in `hivens.widget.api.CompositionKeys`.
 *
 * The packs and what is done to them are not here: they are the packs module's keys,
 * in `hivens.packs.api.PackKeys`, and exist only where the document names that module.
 * Nor are the session and the accounts, which are the accounts module's, in
 * `hivens.accounts.api.AccountKeys`, nor the catalogue and the project the rail
 * describes, which are the catalogue module's, in `hivens.catalogue.api.CatalogueKeys`,
 * nor the theme in use and what decides dark and light, which are the themes module's,
 * in `hivens.themes.api.ThemesKeys`.
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

    /**
     * The window a widget is drawn in, as the host reads it: its size, whether it
     * is maximised, minimised and focused, and what the platform allows. Changed
     * through the window commands in [Commands], never directly.
     */
    val Window = SourceKey<HostWindowState>("window.state", ScopeLevel.Window)

    /** Whether the user let the launcher reach its own server past a certificate it does not trust. */
    val SslBypass = SourceKey<Boolean>("ssl.bypass")

    /** The wallpaper and what is done to it. Changed through [Commands.UpdateBackground]. */
    val Background = SourceKey<BackgroundSettings>("background.settings")

    /** The machine's memory and processor, read once a run. */
    val SystemInfo = SourceKey<SystemFacts>("system.info")

    /** Whether the console is asked to be on screen. Changed through [Commands.SetConsoleVisible]. */
    val ConsoleVisible = SourceKey<Boolean>("console.visible")
}
