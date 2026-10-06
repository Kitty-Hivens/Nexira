package hivens.ui.screens.detail.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.InstanceRuntime
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.jvm.AutomaticHeap
import hivens.core.jvm.JvmArgsPresets
import hivens.core.jvm.JvmConfig
import hivens.core.jvm.SystemMemory
import hivens.launcher.ProfilerProfileStore
import hivens.launcher.component.EarlyLoadingScreen
import hivens.launcher.component.JvmHeapArgs
import hivens.ui.components.JvmArgsBuilderDialog
import hivens.ui.components.RamSelector
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxField
import hivens.ui.nx.NxReveal
import hivens.ui.nx.NxSettingBlock
import hivens.ui.nx.NxSettingGroup
import hivens.ui.nx.NxSettingRow
import hivens.ui.nx.NxSwitch
import hivens.ui.theme.NxInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.nio.file.Path

/**
 * Launch preferences: heap (via the shared [RamSelector]), the Java executable
 * override + JVM-args builder, the optional game-window geometry, and the
 * loader's own loading screen where the pack's loader has one. Every knob
 * writes onto [InstanceRuntime]; the launch path already honours javaPath and
 * jvmArgs, and window geometry is wired behind [InstanceRuntime.windowSizeOverride].
 *
 * Java is shown as what it is by default, a runtime the launcher picks and
 * provisions for this Minecraft version. The path field appears only once somebody
 * asks for their own: an empty field with a sample path in it read as a setting
 * left blank by mistake.
 */
@Composable
internal fun PackRuntimeSection(
    pack: PackInstance,
    instanceDir: Path,
    save: (PackEdit) -> Unit,
) {
    val s = LocalStrings.current
    val profilerStore: ProfilerProfileStore = koinInject()
    val settingsService: ISettingsService = koinInject()
    val javaManager: IJavaManager = koinInject()
    val runtime = pack.runtime

    // One knob, not the runtime as this frame shows it: the write lands on the
    // record as it is by then.
    fun commit(change: (InstanceRuntime) -> InstanceRuntime) = save { it.copy(runtime = change(it.runtime)) }

    // Auto-heap resolution mirrors the old settings tab: the adaptive profile when
    // enabled and present, else the physical-memory heuristic.
    var resolvedAutoMb by remember { mutableStateOf(AutomaticHeap.compute(SystemMemory.totalPhysicalMb())) }
    LaunchedEffect(instanceDir) {
        val adaptiveOn = settingsService.getSettings().adaptiveMemoryEnabled
        val derivedMb = if (adaptiveOn) withContext(Dispatchers.IO) { profilerStore.readProfile(instanceDir)?.derivedHeapMb } else null
        resolvedAutoMb = derivedMb ?: AutomaticHeap.compute(SystemMemory.totalPhysicalMb())
    }

    var showJvmBuilder by remember(pack.id) { mutableStateOf(false) }
    var widthText by remember(pack.id) { mutableStateOf(runtime.windowWidth.toString()) }
    var heightText by remember(pack.id) { mutableStateOf(runtime.windowHeight.toString()) }
    // Asked for, not yet typed: the field shows before a path has been written.
    var ownJava by remember(pack.id) { mutableStateOf(false) }
    val customJava = !runtime.javaPath.isNullOrBlank()

    NxSettingGroup(s.packSettingsMemory) {
        RamSelector(
            isAuto = !runtime.fixedMemory,
            resolvedAutoMb = resolvedAutoMb,
            // Nothing pinned: offer what the next launch would use anyway, so
            // leaving Auto starts from the real number rather than a constant.
            currentMb = runtime.memoryMb.takeIf { it > 0 } ?: resolvedAutoMb,
            onAutoSelected = { commit { rt -> rt.copy(fixedMemory = false) } },
            onValueChanged = { commit { rt -> rt.copy(memoryMb = it, fixedMemory = true) } },
        )
        // What is typed by hand wins, so a heap named in the arguments is the one
        // the game gets and this setting is left on screen saying it does not apply.
        val typedHeap = remember(runtime.jvmArgs) { JvmHeapArgs.inArgs(runtime.jvmArgs) }
        if (typedHeap.isNotEmpty()) {
            NxSettingBlock {
                Text(
                    s.packSettingsMemoryFromArgs(typedHeap.joinToString(" ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = NxInk.quiet,
                )
            }
        }
    }

    NxSettingGroup(s.packSettingsEnvironment) {
        val major = requiredJavaMajor(pack, javaManager)
        NxSettingRow(
            title  = s.packSettingsJava,
            detail = if (customJava) s.packSettingsJavaCustom else major?.let { s.packSettingsJavaManaged(it) } ?: s.packSettingsJavaCustom,
        ) {
            if (customJava || ownJava) {
                NxButton(
                    s.packSettingsJavaReset,
                    onClick = { ownJava = false; commit { rt -> rt.copy(javaPath = null) } },
                    style = NxButtonStyle.Secondary,
                    compact = true,
                )
            } else {
                NxButton(
                    s.packSettingsJavaPickOwn,
                    onClick = { ownJava = true },
                    style = NxButtonStyle.Secondary,
                    compact = true,
                )
            }
        }
        NxReveal(visible = customJava || ownJava) {
            NxSettingBlock {
                NxField(
                    value = runtime.javaPath ?: "",
                    onValueChange = { commit { rt -> rt.copy(javaPath = it.ifBlank { null }) } },
                    placeholder = s.packSettingsJavaPathPlaceholder,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        NxSettingRow(
            title  = s.packSettingsJvmArgs,
            detail = runtime.jvmArgs?.takeIf { it.isNotBlank() } ?: s.packSettingsJvmArgsDefault,
        ) {
            NxButton(
                s.packSettingsJvmArgsEdit,
                onClick = { showJvmBuilder = true },
                style = NxButtonStyle.Secondary,
                compact = true,
            )
        }
    }

    NxSettingGroup(s.packSettingsWindow) {
        NxSettingRow(s.packSettingsWindowOverride, detail = s.packSettingsWindowOverrideDesc) {
            NxSwitch(runtime.windowSizeOverride, { on -> commit { rt -> rt.copy(windowSizeOverride = on) } })
        }

        NxReveal(visible = runtime.windowSizeOverride) {
            NxSettingBlock {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s.packSettingsWidth, style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
                        NxField(
                            value = widthText,
                            onValueChange = { raw ->
                                widthText = raw.filter { it.isDigit() }.take(5)
                                widthText.toIntOrNull()?.takeIf { it in 1..10000 }?.let { commit { rt -> rt.copy(windowWidth = it) } }
                            },
                            placeholder = s.packSettingsWidth,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s.packSettingsHeight, style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
                        NxField(
                            value = heightText,
                            onValueChange = { raw ->
                                heightText = raw.filter { it.isDigit() }.take(5)
                                heightText.toIntOrNull()?.takeIf { it in 1..10000 }?.let { commit { rt -> rt.copy(windowHeight = it) } }
                            },
                            placeholder = s.packSettingsHeight,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        NxSettingRow(s.packSettingsFullscreen) {
            NxSwitch(runtime.fullScreen, { on -> commit { rt -> rt.copy(fullScreen = on) } })
        }

        val manifest = pack.cachedManifest
        if (EarlyLoadingScreen.appliesTo(manifest?.loaderName, manifest?.minecraftVersion)) {
            val choice = runtime.earlyLoadingScreen
            // Read again whenever the choice changes: a launch in between may have rewritten it.
            var packValue by remember(instanceDir) { mutableStateOf<Boolean?>(null) }
            var read by remember(instanceDir) { mutableStateOf(false) }
            LaunchedEffect(instanceDir, choice) {
                packValue = withContext(Dispatchers.IO) { runCatching { EarlyLoadingScreen.readConfig(instanceDir) }.getOrNull() }
                read = true
            }
            val launcherDecides = choice == null && EarlyLoadingScreen.waylandSession
            // Where the pack's own file is the answer, nothing is shown until it has
            // been read: a first frame drawn from the default flipped visibly for a
            // pack that ships the screen off.
            if (read || choice != null || launcherDecides) {
                NxSettingRow(
                    title  = s.packSettingsEarlyScreen,
                    detail = if (launcherDecides) s.packSettingsEarlyScreenWayland else s.packSettingsEarlyScreenDesc,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (choice != null) {
                            NxButton(
                                s.packSettingsEarlyScreenReset,
                                onClick = { commit { rt -> rt.copy(earlyLoadingScreen = null) } },
                                style = NxButtonStyle.Tertiary,
                                compact = true,
                            )
                        }
                        NxSwitch(
                            EarlyLoadingScreen.effective(choice, packValue),
                            { on -> commit { rt -> rt.copy(earlyLoadingScreen = on) } },
                        )
                    }
                }
            }
        }
    }

    if (showJvmBuilder) {
        JvmArgsBuilderDialog(
            // Seed from the instance's stored args (round-tripped back into the
            // structured model, unknown flags kept in the Custom tab) so reopening
            // the editor no longer discards them; falls back to the default preset
            // only when the instance has none yet.
            initial = runtime.jvmArgs?.takeIf { it.isNotBlank() }?.let { JvmConfig.fromArgs(it) }
                ?: JvmArgsPresets.default.config,
            // The runtime the args will be handed to, so the builder does not
            // compose a flag this pack's JDK no longer recognises.
            javaMajor = requiredJavaMajor(pack, javaManager),
            onDismiss = { showJvmBuilder = false },
            onApply = { newArgs ->
                commit { rt -> rt.copy(jvmArgs = newArgs.ifBlank { null }) }
                showJvmBuilder = false
            },
        )
    }
}

/**
 * The Java the next launch would pick for this instance.
 *
 * The mirror declares the runtime in its manifest and is taken at its word. No
 * other source does: what is stored for them is the launcher's own reading of
 * the Minecraft version, taken once at install and never revisited -- so an
 * instance installed while that reading was wrong went on reporting the wrong
 * runtime long after it was corrected. Reading it again here costs nothing and
 * cannot go stale.
 */
private fun requiredJavaMajor(pack: PackInstance, javaManager: IJavaManager): Int? {
    val manifest = pack.cachedManifest ?: return null
    if (pack.packRef.origin == PackOrigin.Mirror) return manifest.javaMajor
    return javaManager.detectJavaVersion(manifest.minecraftVersion)
}
