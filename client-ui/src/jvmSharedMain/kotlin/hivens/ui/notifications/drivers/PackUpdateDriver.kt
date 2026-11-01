package hivens.ui.notifications.drivers

import hivens.core.api.interfaces.IPackRepository
import hivens.core.update.PackUpdateStatus
import hivens.core.update.PackUpdateStatusHub
import hivens.ui.Screen
import hivens.ui.i18n.AppStrings
import hivens.ui.navigation.NavRequests
import hivens.ui.notifications.Kind
import hivens.ui.notifications.NotifAction
import hivens.ui.notifications.NotifGlyph
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Bridges the pack-update status hub to the [NotificationCenter], the way
 * [InstallDriver] bridges installs: background auto-update outcomes surface as
 * toasts instead of dying in the log. `Updated` and `Failed` announce on each
 * transition; a `Pending` build announces once per version (the hub re-emits
 * the whole map on any instance's change, and an auto-update pass re-deriving
 * the same Pending must not re-toast it) and not at all while it is held by
 * policy; `Checking` is noise and stays quiet.
 * Actions navigate through [NavRequests] -- a toast has no place in the
 * composition to reach the back stack directly.
 */
class PackUpdateDriver(
    private val hub: PackUpdateStatusHub,
    private val repository: IPackRepository,
    private val notifications: NotificationCenter,
    private val nav: NavRequests,
    private val appScope: CoroutineScope,
    private val stringsProvider: () -> AppStrings,
) {
    private val lastStatus = HashMap<String, PackUpdateStatus>()
    private val announcedPending = HashMap<String, String>()

    fun start() {
        appScope.launch {
            hub.statuses.collect { statuses ->
                statuses.forEach { (id, status) ->
                    if (lastStatus[id] != status) {
                        lastStatus[id] = status
                        push(id, status)
                    }
                }
                lastStatus.keys.retainAll(statuses.keys)
            }
        }
    }

    private suspend fun push(id: String, status: PackUpdateStatus) {
        val s = stringsProvider()
        val instance = repository.get(id)
        val title = instance?.displayName ?: id
        val iconUrl = instance?.iconUrl
        val sourceKey = "pack-update:$id"
        when (status) {
            PackUpdateStatus.Checking -> Unit
            PackUpdateStatus.UpToDate -> {
                announcedPending.remove(id)
            }
            is PackUpdateStatus.Updated -> {
                announcedPending.remove(id)
                notifications.push(
                    sourceKey = sourceKey,
                    sender    = title,
                    iconUrl   = iconUrl,
                    glyph     = NotifGlyph.Update,
                    severity  = Severity.Success,
                    kind      = Kind.OneShot,
                    title     = s.notifPackUpdated(title, status.toVersion),
                )
            }
            is PackUpdateStatus.Pending -> {
                // Hold means the user chose to stay on the current build. The card and
                // the versions screen still show the newer one; interrupting for it
                // would be nagging about a decision already made.
                if (status.held) return
                if (announcedPending[id] == status.toVersion) return
                announcedPending[id] = status.toVersion
                notifications.push(
                    sourceKey = sourceKey,
                    sender    = title,
                    iconUrl   = iconUrl,
                    glyph     = NotifGlyph.Update,
                    severity  = Severity.Info,
                    kind      = Kind.ActionRequired,
                    title     = s.notifPackUpdatePending(title, status.toVersion),
                    actions   = listOf(
                        NotifAction("open-versions", s.notifActionOpenVersions) { nav.open(Screen.PackVersions(id)) },
                    ),
                )
            }
            is PackUpdateStatus.Failed -> notifications.push(
                sourceKey = sourceKey,
                sender    = title,
                iconUrl   = iconUrl,
                glyph     = NotifGlyph.Update,
                severity  = Severity.Critical,
                kind      = Kind.Sticky,
                title     = s.notifPackUpdateFailed(title),
                body      = status.reason.ifBlank { null },
                actions   = listOf(
                    NotifAction("open-versions", s.notifActionOpenVersions) { nav.open(Screen.PackVersions(id)) },
                ),
            )
        }
    }
}
