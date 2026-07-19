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
internal fun PackContentSection(pack: PackInstance, onInstanceChange: (PackInstance) -> Unit) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    val mirrorClient: IMirrorPackClient = koinInject()
    val controller: LauncherController = koinInject()
    val isMirror = pack.packRef.origin == PackOrigin.Mirror

    var manifest by remember(pack.id) { mutableStateOf<SmrtPackManifest?>(null) }
    var state by remember(pack.id) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var loading by remember(pack.id) { mutableStateOf(isMirror) }

    LaunchedEffect(pack.id) {
        if (!isMirror) {
            loading = false
            return@LaunchedEffect
        }
        val version = pack.pinnedPackVersion ?: pack.packRef.version
        val fetched = runCatching {
            if (!version.isNullOrBlank()) mirrorClient.fetchManifestVersion(pack.packRef.id, version)
            else mirrorClient.fetchManifest(pack.packRef.id)
        }.getOrNull()
        manifest = fetched
        if (fetched != null) state = OptionalContentRules.enabledState(fetched.mods, pack.optionalContent)
        loading = false
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
                    state = next
                    controller.setOptionalModsAsync(pack, m, OptionalContentRules.togglesFrom(m.mods, next))
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
