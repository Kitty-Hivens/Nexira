package hivens.ui.screens.detail.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.core.data.PackInstance
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxSettingBlock
import hivens.ui.nx.NxSettingGroup
import hivens.ui.nx.NxSettingRow
import hivens.ui.puppet.PuppetClick
import hivens.ui.screens.library.LoaderPicker
import hivens.ui.screens.library.loaderNeedsVersion
import hivens.ui.theme.NxInk

/**
 * The loader of a pack the player owns, changeable after the pack exists.
 *
 * Nothing else wrote the loader once a pack was made, so a pack created with the
 * wrong loader version, or left on the latest and wanting a pin, had to be made
 * again. The choices are the create dialog's own. A change is a draft until it is
 * applied, so a version half typed is never what the next launch reads, and it is
 * applied to the record only: the next launch installs the loader, the same way a
 * pack left on the latest settles its version at its first launch.
 *
 * Shown for a local pack only. A tracked pack's loader is its source's, and the
 * next update would put it back.
 */
@Composable
internal fun PackLoaderSection(pack: PackInstance, save: (PackEdit) -> Unit) {
    val s = LocalStrings.current
    val manifest = pack.cachedManifest ?: return
    val storedId = manifest.loaderName.trim().lowercase().takeUnless { it.isEmpty() || it == "vanilla" }
    val storedVersion = manifest.loaderVersion

    var loaderId by remember(pack.id, storedId) { mutableStateOf(storedId) }
    var version by remember(pack.id, storedId, storedVersion) { mutableStateOf(storedVersion) }
    val changed = loaderId != storedId || version.trim() != storedVersion
    val canApply = changed && (!loaderNeedsVersion(loaderId) || version.isNotBlank())

    fun apply() {
        if (canApply) save { p -> withLoader(p, loaderId, version) }
    }

    NxSettingGroup(s.packSettingsLoader) {
        NxSettingRow(s.packLoaderInstalled, detail = s.packLoaderNextLaunch) { Value(runtimeLine(manifest, s)) }
        NxSettingBlock {
            LoaderPicker(
                mcVersion       = manifest.minecraftVersion,
                mcKnown         = true,
                loaderId        = loaderId,
                onLoader        = { loaderId = it },
                loaderVersion   = version,
                onLoaderVersion = { version = it },
                puppetKey       = "packSettings.loaderVersion",
                label           = { FieldLabel(it) },
            )
        }
        // Said before the click rather than after the launch: the folder is left as
        // it is, and a pack of Fabric mods put on Forge starts with none of them.
        if (loaderId != storedId) {
            NxSettingBlock {
                Text(s.packLoaderModsStay, style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
            }
        }
        NxSettingBlock {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (changed) {
                    NxButton(
                        s.packLoaderRevert,
                        onClick = { loaderId = storedId; version = storedVersion },
                        style = NxButtonStyle.Tertiary,
                        compact = true,
                    )
                }
                NxButton(s.packLoaderApply, onClick = ::apply, enabled = canApply, compact = true)
                PuppetClick("packSettings.loader.apply", enabled = canApply) { apply() }
            }
        }
    }
}

/**
 * [pack] on [loaderId] at [version], as the record stores it: "vanilla" for no
 * loader, and no version for vanilla, since a version of nothing would be read as
 * a loader's. A blank version for a loader is kept blank, which the next launch
 * reads as the latest and then pins.
 */
internal fun withLoader(pack: PackInstance, loaderId: String?, version: String): PackInstance =
    pack.copy(
        cachedManifest = pack.cachedManifest?.copy(
            loaderName = loaderId ?: "vanilla",
            loaderVersion = if (loaderId == null) "" else version.trim(),
        ),
    )
