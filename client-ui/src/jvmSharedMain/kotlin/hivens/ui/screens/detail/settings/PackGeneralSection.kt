package hivens.ui.screens.detail.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.data.PackInstance
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxField
import hivens.ui.nx.NxSettingBlock
import hivens.ui.nx.NxSettingGroup
import hivens.ui.nx.NxSettingRow
import hivens.ui.theme.NxInk

/**
 * General identity: the editable name and free-text notes, plus read-only
 * provenance (where the pack came from, what it runs on, its id). Name and notes
 * commit on change straight onto the instance -- the repository replaces by id.
 */
@Composable
internal fun PackGeneralSection(pack: PackInstance, save: (PackEdit) -> Unit) {
    val s = LocalStrings.current

    NxSettingGroup(s.packSettingsIdentity) {
        NxSettingBlock {
            FieldLabel(s.packSettingsName)
            NxField(
                value = pack.displayName,
                onValueChange = { if (it != pack.displayName) save { p -> p.copy(displayName = it) } },
                placeholder = s.packSettingsNamePlaceholder,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        NxSettingBlock {
            FieldLabel(s.packSettingsNotes)
            NxField(
                value = pack.notes,
                onValueChange = { if (it != pack.notes) save { p -> p.copy(notes = it) } },
                placeholder = s.packSettingsNotesPlaceholder,
                singleLine = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            )
        }
    }

    NxSettingGroup(s.packSettingsSource) {
        NxSettingRow(s.packSettingsSource) { Value(pack.packRef.origin.name) }
        pack.cachedManifest?.let { manifest ->
            NxSettingRow(s.packSettingsLoader) { Value(runtimeLine(manifest, s)) }
        }
        pack.forkedFrom?.let { origin ->
            NxSettingRow(s.packSettingsForkedFrom(origin.id))
        }
        NxSettingRow(s.packSettingsPackId) { Value(pack.packRef.id) }
    }
}

/** The label over a field, in the row's own title voice. */
@Composable
internal fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = NxInk.main, fontWeight = FontWeight.Medium)
}

/** A read-only value at a row's far edge. */
@Composable
internal fun Value(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = NxInk.quiet, maxLines = 1)
}
