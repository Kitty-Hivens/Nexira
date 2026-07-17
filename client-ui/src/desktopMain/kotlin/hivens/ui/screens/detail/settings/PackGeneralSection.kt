package hivens.ui.screens.detail.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxField
import hivens.ui.nx.NxRow
import hivens.ui.nx.NxSection
import hivens.ui.theme.NxTheme
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * General identity: the editable name and free-text notes, plus read-only
 * provenance (source, forked-from, pack id). Name and notes commit on change
 * straight onto the instance -- the repository replaces by id.
 */
@Composable
internal fun PackGeneralSection(pack: PackInstance, onInstanceChange: (PackInstance) -> Unit) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    val repo: IPackRepository = koinInject()
    val scope = rememberCoroutineScope()

    fun commit(updated: PackInstance) {
        onInstanceChange(updated)
        scope.launch { repo.put(updated) }
    }

    NxSection(s.packSettingsIdentity) {
        Column(Modifier.fillMaxWidth()) {
            Text(s.packSettingsName, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            NxField(
                value = pack.displayName,
                onValueChange = { if (it != pack.displayName) commit(pack.copy(displayName = it)) },
                placeholder = s.packSettingsNamePlaceholder,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        Column(Modifier.fillMaxWidth()) {
            Text(s.packSettingsNotes, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            NxField(
                value = pack.notes,
                onValueChange = { if (it != pack.notes) commit(pack.copy(notes = it)) },
                placeholder = s.packSettingsNotesPlaceholder,
                singleLine = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(top = 6.dp),
            )
        }
    }

    NxSection(s.packSettingsSource) {
        NxRow(title = s.packSettingsSource, subtitle = pack.packRef.origin.name)
        pack.forkedFrom?.let { origin ->
            NxRow(title = s.packSettingsForkedFrom(origin.id))
        }
        NxRow(title = s.packSettingsPackId, subtitle = pack.packRef.id)
    }
}
