package hivens.ui.widgets

import hivens.launcher.AutoSyncService
import hivens.ui.notifications.PersistedNotification
import hivens.ui.screens.mod.OpenProject
import hivens.widget.model.SourceKey

/**
 * Catalog of reactive data sources widgets bind to via `rememberSource(...)`.
 * Lives in client-ui because only this module can name the source value types
 * (AutoSyncService is client-launcher, PersistedNotification is client-ui); the
 * low-level WidgetDataRegistry keys on the string id alone, so a rule-engine /
 * editor references a source without seeing these types.
 */
object Sources {
    val AutoSync = SourceKey<AutoSyncService.Snapshot>("autosync")
    val Notifications = SourceKey<List<PersistedNotification>>("notifications.archive")
    // "Do not disturb" live state -- the notification-history widget reflects it
    // on its mute toggle; NotificationStack reads the same flow to gate popups.
    val DoNotDisturb = SourceKey<Boolean>("notifications.dnd")
    // The project whose page is open, or null anywhere else in the app. Read by
    // the right rail's project-view family, which is shown on that page and has
    // nothing to say off it.
    val OpenProject = SourceKey<OpenProject?>("mod.open")
}
