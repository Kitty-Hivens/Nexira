package hivens.ui.screens.detail.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.InstanceRuntime
import hivens.core.data.RuntimePrefs
import hivens.core.data.PackInstance
import hivens.core.jvm.AutomaticHeap
import hivens.core.jvm.JvmArgsPresets
import hivens.core.jvm.JvmConfig
import hivens.core.jvm.SystemMemory
import hivens.launcher.ProfilerProfileStore
import hivens.ui.components.JvmArgsBuilderDialog
import hivens.ui.components.RamSelector
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxField
import hivens.ui.nx.NxRow
import hivens.ui.nx.NxSection
import hivens.ui.nx.NxToggle
import hivens.ui.theme.NxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.nio.file.Path

/**
 * Launch preferences: heap (via the shared [RamSelector]), the Java executable
 * override + JVM-args builder, and the optional game-window geometry. Every knob
 * writes onto [InstanceRuntime]; the launch path already honours javaPath and
 * jvmArgs, and window geometry is now wired behind [InstanceRuntime.windowSizeOverride].
 */
@Composable
internal fun PackRuntimeSection(
    pack: PackInstance,
    instanceDir: Path,
    save: (PackInstance) -> Unit,
) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    val profilerStore: ProfilerProfileStore = koinInject()
    val settingsService: ISettingsService = koinInject()
    val scope = rememberCoroutineScope()
    val runtime = pack.runtime

    fun commit(rt: InstanceRuntime) = save(pack.copy(runtime = rt))

    // Auto-heap resolution mirrors the old settings tab: the adaptive profile when
    // enabled and present, else the physical-memory heuristic.
    var resolvedAutoMb by remember { mutableStateOf(AutomaticHeap.compute(SystemMemory.totalPhysicalMb())) }
    LaunchedEffect(instanceDir) {
        val settings = settingsService.getSettings()
        val adaptiveOn = settings.experimentalFeaturesEnabled && settings.adaptiveMemoryEnabled
        val derivedMb = if (adaptiveOn) withContext(Dispatchers.IO) { profilerStore.readProfile(instanceDir)?.derivedHeapMb } else null
        resolvedAutoMb = derivedMb ?: AutomaticHeap.compute(SystemMemory.totalPhysicalMb())
    }

    var showJvmBuilder by remember(pack.id) { mutableStateOf(false) }
    var widthText by remember(pack.id) { mutableStateOf(runtime.windowWidth.toString()) }
    var heightText by remember(pack.id) { mutableStateOf(runtime.windowHeight.toString()) }

    NxSection(s.packSettingsMemory) {
        RamSelector(
            isAuto = !runtime.fixedMemory,
            resolvedAutoMb = resolvedAutoMb,
            // Nothing pinned: offer what the next launch would use anyway, so
            // leaving Auto starts from the real number rather than a constant.
            currentMb = runtime.memoryMb.takeIf { it > 0 } ?: resolvedAutoMb,
            onAutoSelected = { commit(runtime.copy(fixedMemory = false)) },
            onValueChanged = { commit(runtime.copy(memoryMb = it, fixedMemory = true)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    NxSection(s.packSettingsEnvironment) {
        val major = pack.cachedManifest?.javaMajor
        Column(Modifier.fillMaxWidth()) {
            Text(s.packSettingsJava, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            NxField(
                value = runtime.javaPath ?: "",
                onValueChange = { commit(runtime.copy(javaPath = it.ifBlank { null })) },
                placeholder = s.packSettingsJavaPathPlaceholder,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (runtime.javaPath.isNullOrBlank()) major?.let { s.packSettingsJavaManaged(it) } ?: s.packSettingsJavaCustom
                    else s.packSettingsJavaCustom,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (!runtime.javaPath.isNullOrBlank()) {
                    NxButton(
                        s.packSettingsJavaReset,
                        onClick = { commit(runtime.copy(javaPath = null)) },
                        style = NxButtonStyle.Tertiary,
                        compact = true,
                    )
                }
            }
        }

        NxRow(
            title = s.packSettingsJvmArgs,
            subtitle = runtime.jvmArgs?.takeIf { it.isNotBlank() } ?: s.packSettingsJvmArgsDefault,
        ) {
            NxButton(
                s.packSettingsJvmArgsEdit,
                onClick = { showJvmBuilder = true },
                style = NxButtonStyle.Secondary,
                compact = true,
            )
        }
    }

    NxSection(s.packSettingsWindow) {
        NxToggle(
            s.packSettingsWindowOverride,
            runtime.windowSizeOverride,
            description = s.packSettingsWindowOverrideDesc,
            icon = NxIcon.OpenInFull,
        ) { commit(runtime.copy(windowSizeOverride = it)) }

        if (runtime.windowSizeOverride) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.packSettingsWidth, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                    NxField(
                        value = widthText,
                        onValueChange = { raw ->
                            widthText = raw.filter { it.isDigit() }.take(5)
                            widthText.toIntOrNull()?.takeIf { it in 1..10000 }?.let { commit(runtime.copy(windowWidth = it)) }
                        },
                        placeholder = s.packSettingsWidth,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(s.packSettingsHeight, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                    NxField(
                        value = heightText,
                        onValueChange = { raw ->
                            heightText = raw.filter { it.isDigit() }.take(5)
                            heightText.toIntOrNull()?.takeIf { it in 1..10000 }?.let { commit(runtime.copy(windowHeight = it)) }
                        },
                        placeholder = s.packSettingsHeight,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        }

        NxToggle(s.packSettingsFullscreen, runtime.fullScreen, icon = NxIcon.Tv) {
            commit(runtime.copy(fullScreen = it))
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
            javaMajor = pack.cachedManifest?.javaMajor,
            onDismiss = { showJvmBuilder = false },
            onApply = { newArgs ->
                commit(runtime.copy(jvmArgs = newArgs.ifBlank { null }))
                showJvmBuilder = false
            },
        )
    }
}
