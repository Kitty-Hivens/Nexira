package hivens.ui.widgets.home.new

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.config.Branding
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.update.PackUpdateStatus
import hivens.core.update.PackUpdateStatusHub
import hivens.core.update.UpdateDirection
import hivens.ui.Screen
import hivens.ui.components.LauncherUpdateState
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxRow
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import org.koin.compose.koinInject

// What moved since you last looked: a new launcher build, and the packs that have a
// build waiting or have just taken one. Each line goes where that thing is acted on,
// the launcher's own update dialog or the pack's versions screen, so this is a way in
// rather than a second place to act.
//
// Nothing new, nothing drawn. Home is somebody's own space and a shelf that says
// "nothing to report" is furniture, so the widget is absent until it has something.
// Its panel is declared, so the editor's surface rows move it like the others.
@Widget(
    id = "home.new.whatsnew",
    enter = "rise",
    displayName = "widget.home.new.whatsnew",
    surface = """{"fill":"panel"}""",
    minWidth = 240, minHeight = 60,
    maxWidth = 2400, maxHeight = 800,
)
@Composable
@Suppress("UNUSED_PARAMETER")
fun HomeNewWhatsNew(instance: WidgetInstance) {
    val ctx = LocalHomeNewContext.current
    val s = LocalStrings.current
    val updateState: LauncherUpdateState = koinInject()
    val launcher by updateState.available.collectAsState()
    val hub: PackUpdateStatusHub = koinInject()
    val statuses by hub.statuses.collectAsState()
    val repo: IPackRepository = koinInject()
    val packs by remember { repo.observe() }.collectAsState()

    val packNews = remember(packs, statuses) { packNews(packs, statuses) }
    if (launcher == null && packNews.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text       = s.homeWhatsNewTitle,
            style      = MaterialTheme.typography.titleSmall,
            color      = NxInk.main,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.padding(bottom = 4.dp),
        )
        launcher?.let { update ->
            NxRow(
                title    = s.homeWhatsNewLauncher(Branding.TITLE, update.version),
                subtitle = s.homeWhatsNewLauncherHint,
                icon     = NxIcon.NewReleases,
                iconTint = NxColor.lead(),
                onClick  = { updateState.requestDetails() },
                trailing = { Symbol(NxIcon.ChevronRight, null, tint = NxInk.quiet) },
            )
        }
        packNews.forEach { (pack, status) ->
            NxRow(
                title    = pack.displayName,
                subtitle = when (status) {
                    is PackUpdateStatus.Pending ->
                        if (status.direction == UpdateDirection.Older) s.homeWhatsNewPackRollback(status.toVersion)
                        else s.homeWhatsNewPackReady(status.toVersion)
                    is PackUpdateStatus.Updated -> s.homeWhatsNewPackUpdated(status.toVersion)
                    else -> null
                },
                icon     = when (status) {
                    is PackUpdateStatus.Updated -> NxIcon.CheckCircle
                    is PackUpdateStatus.Pending ->
                        if (status.direction == UpdateDirection.Older) NxIcon.History else NxIcon.Update
                    else -> NxIcon.Update
                },
                iconTint = NxColor.lead(),
                onClick  = { ctx.onScreenChange(Screen.PackVersions(pack.id)) },
                trailing = { Symbol(NxIcon.ChevronRight, null, tint = NxInk.quiet) },
            )
        }
    }
}

/**
 * The packs worth a line, most recently played first: a build waiting, or one just
 * taken. A pack that is up to date, being checked, or failed to check says nothing
 * here; a failure is the notification's to report and the pack page's to explain.
 */
internal fun packNews(
    packs: List<PackInstance>,
    statuses: Map<String, PackUpdateStatus>,
): List<Pair<PackInstance, PackUpdateStatus>> =
    packs
        .sortedByDescending { it.lastPlayedEpochOrZero }
        .mapNotNull { pack ->
            when (val status = statuses[pack.id]) {
                is PackUpdateStatus.Pending, is PackUpdateStatus.Updated -> pack to status
                else -> null
            }
        }
