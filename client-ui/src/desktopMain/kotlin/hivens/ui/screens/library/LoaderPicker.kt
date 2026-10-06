package hivens.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.runtime.loader.LoaderVersionOption
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxContextMenu
import hivens.ui.nx.NxField
import hivens.ui.nx.NxMenuAlign
import hivens.ui.nx.NxMenuItem
import hivens.ui.nx.NxMenuMark
import hivens.ui.puppet.PuppetField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** The loaders a local pack can be made with, as label and registry id. Null is vanilla. */
internal val LOADER_CHOICES: List<Pair<String, String?>> = listOf(
    "Vanilla" to null, "Fabric" to "fabric", "Legacy Fabric" to "legacy-fabric", "Forge" to "forge", "NeoForge" to "neoforge",
    "Quilt" to "quilt",
    "Cleanroom" to "cleanroom", "lwjgl3ify" to "lwjgl3ify",
)

/**
 * Loaders whose builds have no index to resolve a latest from, so a version has to
 * be named.
 */
internal fun loaderNeedsVersion(loaderId: String?): Boolean = loaderId in LOADERS_WITHOUT_LATEST

private val LOADERS_WITHOUT_LATEST = setOf("cleanroom", "lwjgl3ify")

/**
 * The loader chips and, for a loader, its version field with what the loader
 * publishes for [mcVersion] listed under it. One composable for the create dialog
 * and the pack's own settings, so a pack is changed with the choices it was made
 * with.
 *
 * The versions are asked for only once [mcKnown] says [mcVersion] is one Mojang
 * lists, not on every keystroke of one being typed. The field stays free text: a
 * build the listing does not carry is still one a person may name. A different
 * loader clears the version, since a version typed for one loader is not a version
 * of another.
 *
 * [label] draws the caption over the version field in the caller's own voice.
 */
@Composable
internal fun LoaderPicker(
    mcVersion: String,
    mcKnown: Boolean,
    loaderId: String?,
    onLoader: (String?) -> Unit,
    loaderVersion: String,
    onLoaderVersion: (String) -> Unit,
    puppetKey: String,
    label: @Composable (String) -> Unit,
) {
    val s = LocalStrings.current
    val provisioner: RuntimeProvisioner = koinInject()

    var loaderVersions by remember { mutableStateOf<List<LoaderVersionOption>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(loaderId, mcVersion, mcKnown) {
        loaderVersions = emptyList()
        if (loaderId == null || !mcKnown) return@LaunchedEffect
        delay(300)
        loaderVersions = runCatching {
            withContext(Dispatchers.IO) { provisioner.availableLoaderVersions(loaderId, mcVersion) }
        }.getOrDefault(emptyList())
    }
    val matches = remember(loaderVersion, loaderVersions) {
        val typed = loaderVersion.trim()
        (if (typed.isEmpty()) loaderVersions else loaderVersions.filter { it.version.contains(typed, ignoreCase = true) }).take(60)
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LOADER_CHOICES.forEach { (name, id) ->
            NxChoiceChip(label = name, selected = loaderId == id) {
                if (loaderId != id) {
                    onLoaderVersion("")
                    onLoader(id)
                }
            }
        }
    }

    if (loaderId != null) {
        label(s.createPackLoaderVersion)
        Box {
            NxField(
                value = loaderVersion,
                onValueChange = { onLoaderVersion(it); menuOpen = true },
                placeholder = if (loaderNeedsVersion(loaderId)) s.createPackLoaderVersionRequired else s.createPackLoaderVersionLatest,
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) menuOpen = true },
            )
            NxContextMenu(
                expanded         = menuOpen && matches.isNotEmpty(),
                onDismissRequest = { menuOpen = false },
                align            = NxMenuAlign.Start,
                maxHeight        = 240.dp,
                matchAnchorWidth = true,
            ) {
                matches.forEach { option ->
                    NxMenuItem(
                        label = option.version,
                        hint = when {
                            option.recommended -> s.createPackLoaderRecommended
                            !option.stable -> s.createPackLoaderPreRelease
                            else -> null
                        },
                        selected = option.version == loaderVersion,
                        mark = NxMenuMark.Radio,
                    ) {
                        onLoaderVersion(option.version)
                        menuOpen = false
                    }
                }
            }
        }
        PuppetField(puppetKey, loaderVersion) { onLoaderVersion(it); menuOpen = true }
    }
}
