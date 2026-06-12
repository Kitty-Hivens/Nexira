package hivens.ui.widgets

import hivens.launcher.AutoSyncService
import hivens.ui.notifications.PersistedNotification
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
}
