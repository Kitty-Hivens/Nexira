package hivens.ui.widgets

import hivens.core.activity.Activity
import hivens.ui.notifications.PersistedNotification
import hivens.ui.screens.mod.OpenProject
import hivens.widget.model.SourceKey

/**
 * Catalog of reactive data sources widgets bind to via `rememberSource(...)`.
 * Lives in client-ui because only this module can name the source value types
 * (Activity is client-core, PersistedNotification is client-ui); the low-level
 * WidgetDataRegistry keys on the string id alone, so a rule-engine / editor
 * references a source without seeing these types.
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
}
