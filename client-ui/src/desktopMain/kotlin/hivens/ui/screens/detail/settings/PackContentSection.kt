package hivens.ui.screens.detail.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPresence
import hivens.core.api.interfaces.IMirrorPackClient
import hivens.core.data.OptionalContentRules
import hivens.core.data.PackInstance
import hivens.launcher.launch.LauncherController
import hivens.ui.screens.library.content.problemReason
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxSettingBlock
import hivens.ui.nx.NxSettingGroup
import hivens.ui.nx.NxSettingRow
import hivens.ui.nx.NxSwitch
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import hivens.ui.theme.NxColor
import hivens.ui.icons.Symbol
import hivens.ui.icons.NxIcon
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject

/**
 * Optional content for a mirror pack: the curator's optional mods and the resource
 * and shader packs it lets the player switch off, as switches,
 * driven by the same [OptionalContentRules] pipeline the Content tab uses (a
 * flip relabels the `.disabled` files off the app scope). The manifest is
 * fetched for the installed build; an offline fetch collapses to a plain
 * unavailable state. Only a mirror pack reaches this section at all, see
 * [PackSettingsCategory.needsOptionalContent].
 *
 * A switch here can move others: turning a mod on turns on what it needs and off
 * what it cannot run beside. The group says so before anyone flips one, because a
 * neighbouring switch moving on its own reads as a fault when nothing explained it.
 */
@Composable
internal fun PackContentSection(pack: PackInstance, adopt: (PackEdit) -> Unit) {
    val s = LocalStrings.current
    val mirrorClient: IMirrorPackClient = koinInject()
    val controller: LauncherController = koinInject()
    val version = pack.pinnedPackVersion ?: pack.packRef.version

    var manifest by remember(pack.id) { mutableStateOf<SmrtPackManifest?>(null) }
    var loading by remember(pack.id) { mutableStateOf(true) }

    // Keyed on the installed build, not the instance id: an update applied in the
    // footer of this same sheet leaves the id alone, and the optional list it
    // offers belongs to the build that is now on disk.
    LaunchedEffect(pack.id, version) {
        loading = true
        // A cancellation is passed on rather than read as offline: the effect is
        // restarted by a new build, and the old one finishing as "unavailable" wrote
        // over the loading state of the fetch that replaced it.
        val fetched = try {
            if (!version.isNullOrBlank()) mirrorClient.fetchManifestVersion(pack.packRef.id, version)
            else mirrorClient.fetchManifest(pack.packRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        manifest = fetched
        loading = false
    }

    // The switches read the instance record rather than a copy of it seeded once:
    // the Content tab writes the same field, and this is what keeps the two
    // surfaces from telling the user different things about the same mod.
    val state = remember(manifest, pack.optionalContent) {
        manifest?.let { OptionalContentRules.enabledState(it.mods, pack.optionalContent) }.orEmpty()
    }
    val assetState = remember(manifest, pack.optionalContent) {
        manifest?.let { OptionalContentRules.assetState(it.assets, pack.optionalContent) }.orEmpty()
    }

    val optional = remember(manifest) { manifest?.let { OptionalContentRules.optionalMods(it.mods) }.orEmpty() }
    val optionalAssets = remember(manifest) { manifest?.let { OptionalContentRules.optionalAssets(it.assets) }.orEmpty() }

    // The whole choice in one write, so a flip of a mod does not drop what was
    // chosen about the assets, nor the other way round.
    fun choose(m: SmrtPackManifest, mods: Map<String, Boolean>, assets: Map<String, Boolean>) {
        val toggles = OptionalContentRules.togglesFrom(m, mods, assets)
        // Shown at once and composed onto by the next flip: the write is the
        // launcher's and lands behind it, and a pair of flips made inside that
        // window must not both start from the record.
        adopt { it.copy(optionalContent = toggles) }
        controller.setOptionalModsAsync(pack, m, toggles)
    }
    val problems = remember(manifest, state) { manifest?.let { OptionalContentRules.problems(it.mods, state) }.orEmpty() }

    NxSettingGroup(s.packSettingsOptional) {
        when {
            loading -> Muted(s.packSettingsContentLoading)
            manifest == null -> Muted(s.packSettingsContentUnavailable)
            optional.isEmpty() && optionalAssets.isEmpty() -> Muted(s.packSettingsOptionalNone)
            else -> {
                Muted(s.packSettingsOptionalCoToggle)
                optional.forEach { mod ->
                    val presence = mod.display?.presenceClass
                    val problem = problems[mod.filename]?.firstOrNull()
                    // What the rules say will go wrong in place of the description, and
                    // nothing more: the switch still does what the player asks.
                    NxSettingRow(mod.display?.name ?: mod.filename, detail = problem?.let { problemReason(it, s) } ?: mod.display?.description) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (problem != null) Symbol(NxIcon.Warning, contentDescription = null, tint = NxColor.status(Status.Error), size = 18.dp)
                            presenceLabel(presence, s)?.let { NxMetaChip(it, tone = NxMetaChipTone.Surface) }
                            NxSwitch(state[mod.filename] ?: mod.defaultEnabled, onCheckedChange = { enable ->
                                val m = manifest ?: return@NxSwitch
                                choose(m, OptionalContentRules.applyToggle(m.mods, state, mod.filename, enable), assetState)
                            })
                        }
                    }
                }
                optionalAssets.forEach { asset ->
                    NxSettingRow(asset.display?.name ?: asset.dest.substringAfterLast('/'), detail = asset.display?.description) {
                        NxSwitch(assetState[asset.dest] ?: true, onCheckedChange = { enable ->
                            val m = manifest ?: return@NxSwitch
                            choose(m, state, assetState + (asset.dest to enable))
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun Muted(text: String) {
    NxSettingBlock {
        Text(text, style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
    }
}

/** Side badge for an optional entry; `required` and unknown values render none. */
private fun presenceLabel(presence: SmrtPresence?, s: AppStrings): String? = when (presence) {
    SmrtPresence.OptionalClient -> s.packContentPresenceClient
    SmrtPresence.OptionalServer -> s.packContentPresenceServer
    SmrtPresence.OptionalBoth   -> s.packContentPresenceBoth
    SmrtPresence.Coremod        -> s.packContentPresenceCoremod
    SmrtPresence.Required, null -> null
}
