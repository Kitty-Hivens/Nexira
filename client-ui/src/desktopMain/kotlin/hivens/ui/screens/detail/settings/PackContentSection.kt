package hivens.ui.screens.detail.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPresence
import hivens.core.api.interfaces.IMirrorPackClient
import hivens.core.data.OptionalContentRules
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.launcher.launch.LauncherController
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxSection
import hivens.ui.nx.NxToggle
import hivens.ui.theme.NxTheme
import org.koin.compose.koinInject

/**
 * Optional content for a mirror pack: the curator's optional mods as toggles,
 * driven by the same [OptionalContentRules] pipeline the Content tab uses (a
 * flip relabels the `.disabled` files off the app scope). The manifest is
 * fetched for the installed build; a local pack or an offline fetch collapses to
 * a plain empty/unavailable state.
 */
@Composable
internal fun PackContentSection(pack: PackInstance, adopt: (PackInstance) -> Unit) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    val mirrorClient: IMirrorPackClient = koinInject()
    val controller: LauncherController = koinInject()
    val isMirror = pack.packRef.origin == PackOrigin.Mirror
    val version = pack.pinnedPackVersion ?: pack.packRef.version

    var manifest by remember(pack.id) { mutableStateOf<SmrtPackManifest?>(null) }
    var loading by remember(pack.id) { mutableStateOf(isMirror) }

    // Keyed on the installed build, not the instance id: an update applied in the
    // footer of this same window leaves the id alone, and the optional list it
    // offers belongs to the build that is now on disk.
    LaunchedEffect(pack.id, version) {
        if (!isMirror) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        val fetched = runCatching {
            if (!version.isNullOrBlank()) mirrorClient.fetchManifestVersion(pack.packRef.id, version)
            else mirrorClient.fetchManifest(pack.packRef.id)
        }.getOrNull()
        manifest = fetched
        loading = false
    }

    // The switches read the instance record rather than a copy of it seeded once:
    // the Content tab writes the same field, and this is what keeps the two
    // surfaces from telling the user different things about the same mod.
    val state = remember(manifest, pack.optionalContent) {
        manifest?.let { OptionalContentRules.enabledState(it.mods, pack.optionalContent) }.orEmpty()
    }

    val optional = remember(manifest) { manifest?.let { OptionalContentRules.optionalMods(it.mods) }.orEmpty() }

    NxSection(s.packSettingsOptional) {
        when {
            loading -> Muted(s.packSettingsContentLoading, colors.textSecondary)
            !isMirror -> Muted(s.packSettingsOptionalNone, colors.textSecondary)
            manifest == null -> Muted(s.packSettingsContentUnavailable, colors.textSecondary)
            optional.isEmpty() -> Muted(s.packSettingsOptionalNone, colors.textSecondary)
            else -> optional.forEach { mod ->
                val presence = mod.display?.presenceClass
                NxToggle(
                    mod.display?.name ?: mod.filename,
                    state[mod.filename] ?: mod.defaultEnabled,
                    icon = NxIcon.Widgets,
                    trailing = presenceLabel(presence, s)?.let { label -> { NxMetaChip(label, tone = NxMetaChipTone.Surface) } },
                ) { enable ->
                    val m = manifest ?: return@NxToggle
                    val next = OptionalContentRules.applyToggle(m.mods, state, mod.filename, enable)
                    val toggles = OptionalContentRules.togglesFrom(m.mods, next)
                    // Shown at once and composed onto by the next flip: the write
                    // is the launcher's and lands behind it, and a pair of flips
                    // made inside that window must not both start from the record.
                    adopt(pack.copy(optionalContent = toggles))
                    controller.setOptionalModsAsync(pack, m, toggles)
                }
            }
        }
    }
}

@Composable
private fun Muted(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** Side badge for an optional entry; `required` and unknown values render none. */
private fun presenceLabel(presence: SmrtPresence?, s: AppStrings): String? = when (presence) {
    SmrtPresence.OptionalClient -> s.packContentPresenceClient
    SmrtPresence.OptionalServer -> s.packContentPresenceServer
    SmrtPresence.OptionalBoth   -> s.packContentPresenceBoth
    SmrtPresence.Coremod        -> s.packContentPresenceCoremod
    SmrtPresence.Required, null -> null
}
