package hivens.ui.widgets

import hivens.widget.model.CommandKey

/**
 * Catalog of commands widgets fire via `rememberCommand(...)` / `rememberAction(...)`
 * -- the write counterpart of [Sources]. Lives in client-ui for the same reason:
 * the backing services are named here, while the WidgetCommandRegistry keys on the
 * string id alone.
 *
 * Only commands with a live consumer (or a deliberately-seamed one) are listed: a
 * declared key with no registration would throw on dispatch. Music / console / the
 * launch path enter as their widgets migrate off direct injection.
 */
object Commands {
    val ClearNotifications = CommandKey<Unit>("notifications.clear")
    val CheckUpdate = CommandKey<Unit>("update.check")
}
