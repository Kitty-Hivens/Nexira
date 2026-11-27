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
import hivens.packs.api.PackKeys
import hivens.packs.api.PackUpdateView
import hivens.packs.api.PackView
import hivens.ui.components.LauncherUpdateState
import hivens.ui.i18n.nexiraStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.navigation.LauncherConfigs
import hivens.ui.nx.NxRow
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import org.koin.compose.koinInject
import hivens.pages.api.PagesKeys

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
    needs = ["nav.show", "packs.library", "packs.updates"],
)
@Composable
@Suppress("UNUSED_PARAMETER")
fun HomeNewWhatsNew(instance: WidgetInstance) {
    val show = rememberScopedCommand(PagesKeys.Show) ?: return
    val packs = rememberScopedSource(PackKeys.Library).valueOrNull() ?: return
    val s = nexiraStrings()
    val updateState: LauncherUpdateState = koinInject()
    val launcher by updateState.available.collectAsState()
    val statuses = rememberScopedSource(PackKeys.Updates).valueOrNull() ?: return

    val packNews = remember(packs, statuses) { packNews(packs, statuses) }
    if (launcher == null && packNews.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text       = s.text("homeWhatsNewTitle"),
            style      = MaterialTheme.typography.titleSmall,
            color      = NxInk.main,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.padding(bottom = 4.dp),
        )
        launcher?.let { update ->
            NxRow(
                title    = s.text("homeWhatsNewLauncher", Branding.TITLE, update.version),
                subtitle = s.text("homeWhatsNewLauncherHint"),
                icon     = NxIcon.FiberNew,
                iconTint = NxColor.lead(),
                onClick  = { updateState.requestDetails() },
                trailing = { Symbol(NxIcon.ChevronRight, null, tint = NxInk.quiet) },
            )
        }
        packNews.forEach { (pack, status) ->
            NxRow(
                title    = pack.name,
                subtitle = when (status) {
                    is PackUpdateView.Pending ->
                        if (status.direction == PackUpdateView.Pending.OLDER) s.text("homeWhatsNewPackRollback", status.toVersion)
                        else s.text("homeWhatsNewPackReady", status.toVersion)
                    is PackUpdateView.Updated -> s.text("homeWhatsNewPackUpdated", status.toVersion)
                    else -> null
                },
                icon     = when (status) {
                    is PackUpdateView.Updated -> NxIcon.CheckCircle
                    is PackUpdateView.Pending ->
                        if (status.direction == PackUpdateView.Pending.OLDER) NxIcon.History else NxIcon.Update
                    else -> NxIcon.Update
                },
                iconTint = NxColor.lead(),
                onClick  = { show(LauncherConfigs.packVersions(pack.id)) },
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
    packs: List<PackView>,
    statuses: Map<String, PackUpdateView>,
): List<Pair<PackView, PackUpdateView>> =
    packs
        .sortedWith(RecentFirst)
        .mapNotNull { pack ->
            when (val status = statuses[pack.id]) {
                is PackUpdateView.Pending, is PackUpdateView.Updated -> pack to status
                else -> null
            }
        }
