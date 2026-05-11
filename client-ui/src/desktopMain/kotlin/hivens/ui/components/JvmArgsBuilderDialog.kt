package hivens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hivens.core.jvm.*
import hivens.ui.theme.CelestiaTheme

/**
 * Visual builder for the [hivens.core.data.InstanceProfile.jvmArgs] string.
 *
 * Opens as a modal dialog. User picks a preset (Aikar's flags / Heavy
 * modded / ZGC / etc.), then optionally fine-tunes individual knobs in
 * categorised tabs. A live preview at the bottom shows the resulting
 * arg string. [onApply] receives the composed args when the user clicks
 * Apply; [onDismiss] when they Cancel or click outside.
 *
 * The dialog is stateful — it holds a [JvmConfig] in remember and the
 * widgets mutate it. Apply emits the composed args; nothing is written
 * to the launcher's storage from here.
 */
@Composable
fun JvmArgsBuilderDialog(
    initial: JvmConfig = JvmArgsPresets.default.config,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var config by remember { mutableStateOf(initial) }
    var selectedPresetId by remember { mutableStateOf<String?>(null) }
    var selectedTabIdx by remember { mutableStateOf(0) }

    val tabs = remember {
        listOf(
            "GC" to Icons.Default.Memory,
            "G1 / Z / Shenandoah" to Icons.Default.Tune,
            "AppCDS" to Icons.Default.Bolt,
            "JIT" to Icons.Default.Speed,
            "Performance" to Icons.Default.Whatshot,
            "JFR" to Icons.Default.Insights,
            "Custom" to Icons.Default.Code,
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.width(820.dp).heightIn(min = 540.dp, max = 720.dp),
            shape = RoundedCornerShape(16.dp),
            color = CelestiaTheme.colors.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                // ── Header ───────────────────────────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Tune,
                        null,
                        tint = CelestiaTheme.colors.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "JVM Args Builder",
                            color = CelestiaTheme.colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Pick a preset or compose flags by hand. Result lands in jvmArgs.",
                            color = CelestiaTheme.colors.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null, tint = CelestiaTheme.colors.textSecondary)
                    }
                }

                Divider(color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.15f))

                // ── Preset picker ──────────────────────────────────────
                PresetPickerRow(
                    selectedId = selectedPresetId,
                    onSelected = { preset ->
                        selectedPresetId = preset.id
                        config = preset.config
                    },
                )

                Divider(color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.15f))

                // ── Tab row ────────────────────────────────────────────
                ScrollableTabRow(
                    selectedTabIndex = selectedTabIdx,
                    edgePadding = 16.dp,
                    containerColor = CelestiaTheme.colors.surface,
                    contentColor = CelestiaTheme.colors.primary,
                ) {
                    tabs.forEachIndexed { idx, (label, icon) ->
                        Tab(
                            selected = selectedTabIdx == idx,
                            onClick = { selectedTabIdx = idx },
                            text = { Text(label, fontSize = 13.sp) },
                            icon = { Icon(icon, null, modifier = Modifier.size(18.dp)) },
                            selectedContentColor = CelestiaTheme.colors.primary,
                            unselectedContentColor = CelestiaTheme.colors.textSecondary,
                        )
                    }
                }

                // ── Tab content (scrollable) ───────────────────────────
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    when (selectedTabIdx) {
                        0 -> GcTabContent(config) { config = it; selectedPresetId = null }
                        1 -> GcTuningTabContent(config) { config = it; selectedPresetId = null }
                        2 -> CdsTabContent(config) { config = it; selectedPresetId = null }
                        3 -> JitTabContent(config) { config = it; selectedPresetId = null }
                        4 -> PerfTabContent(config) { config = it; selectedPresetId = null }
                        5 -> JfrTabContent(config) { config = it; selectedPresetId = null }
                        6 -> CustomTabContent(config) { config = it; selectedPresetId = null }
                    }
                }

                // ── Live preview ───────────────────────────────────────
                ArgsPreviewBox(config)

                // ── Footer ─────────────────────────────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = CelestiaTheme.colors.textSecondary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onApply(config.toArgString()) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CelestiaTheme.colors.primary,
                        ),
                    ) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Apply to jvmArgs")
                    }
                }
            }
        }
    }
}

// ─── Preset picker ────────────────────────────────────────────────────────

@Composable
private fun PresetPickerRow(
    selectedId: String?,
    onSelected: (JvmPreset) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Text(
            "Presets",
            color = CelestiaTheme.colors.textSecondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JvmArgsPresets.all.forEach { preset ->
                val isSelected = preset.id == selectedId
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelected(preset) },
                    label = { Text(preset.displayName.substringBefore(" ("), fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CelestiaTheme.colors.primary.copy(alpha = 0.2f),
                        selectedLabelColor = CelestiaTheme.colors.primary,
                    ),
                )
            }
        }
        // Show description of currently-selected preset (if any)
        val selectedPreset = JvmArgsPresets.all.firstOrNull { it.id == selectedId }
        if (selectedPreset != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                selectedPreset.description,
                color = CelestiaTheme.colors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ─── Tab: GC algorithm ────────────────────────────────────────────────────

@Composable
private fun GcTabContent(config: JvmConfig, onChange: (JvmConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Garbage Collector")

        GcOption("G1GC", "Recommended for modded MC, 4-32 GB heap.",
            selected = config.gc == GcChoice.G1) {
            onChange(config.copy(gc = GcChoice.G1))
        }
        GcOption("ZGC", "Sub-millisecond pauses. Java 17+, 16+ GB heap. Generational on Java 21+.",
            selected = config.gc == GcChoice.Z) {
            onChange(config.copy(gc = GcChoice.Z))
        }
        GcOption("Shenandoah", "Concurrent low-pause from OpenJDK/Liberica. Java 17+.",
            selected = config.gc == GcChoice.Shenandoah) {
            onChange(config.copy(gc = GcChoice.Shenandoah))
        }
        GcOption("ParallelGC", "Throughput-first. Long stop-the-world pauses. Almost never the right pick.",
            selected = config.gc == GcChoice.Parallel) {
            onChange(config.copy(gc = GcChoice.Parallel))
        }
        GcOption("SerialGC", "Single-threaded. Tiny heaps only (< 1 GB).",
            selected = config.gc == GcChoice.Serial) {
            onChange(config.copy(gc = GcChoice.Serial))
        }
    }
}

@Composable
private fun GcOption(label: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) CelestiaTheme.colors.primary.copy(alpha = 0.12f)
                else CelestiaTheme.colors.background.copy(alpha = 0.4f)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = CelestiaTheme.colors.primary,
                unselectedColor = CelestiaTheme.colors.textSecondary,
            ))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = CelestiaTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
            Text(hint, color = CelestiaTheme.colors.textSecondary,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ─── Tab: per-GC tuning ───────────────────────────────────────────────────

@Composable
private fun GcTuningTabContent(config: JvmConfig, onChange: (JvmConfig) -> Unit) {
    when (config.gc) {
        GcChoice.G1 -> G1TuningPanel(config.g1) { onChange(config.copy(g1 = it)) }
        GcChoice.Z -> ZgcTuningPanel(config.zgc) { onChange(config.copy(zgc = it)) }
        GcChoice.Shenandoah -> ShenandoahTuningPanel(config.shenandoah) { onChange(config.copy(shenandoah = it)) }
        GcChoice.Parallel, GcChoice.Serial -> Text(
            "No tuning available for ${config.gc.name}GC. Switch to G1, Z, or Shenandoah on the GC tab.",
            color = CelestiaTheme.colors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun G1TuningPanel(g1: G1Tuning, onChange: (G1Tuning) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader("G1GC tuning")
        SliderField(
            label = "MaxGCPauseMillis", hint = "Target max pause time. Lower = more frequent collections.",
            value = g1.maxPauseMs.toFloat(), valueRange = 50f..500f, steps = 8,
            display = "${g1.maxPauseMs} ms",
        ) { onChange(g1.copy(maxPauseMs = it.toInt())) }
        SliderField(
            label = "G1HeapRegionSize", hint = "Region size in MB. Larger = fewer regions, less metadata.",
            value = g1.regionSizeMb.toFloat(), valueRange = 1f..32f, steps = 4,
            display = "${g1.regionSizeMb} MB",
        ) { onChange(g1.copy(regionSizeMb = it.toInt())) }
        SliderField(
            label = "G1NewSizePercent", hint = "Min young generation as % of heap. Aikar: 30.",
            value = g1.newSizePercent.toFloat(), valueRange = 5f..80f, steps = 14,
            display = "${g1.newSizePercent}%",
        ) { onChange(g1.copy(newSizePercent = it.toInt())) }
        SliderField(
            label = "G1MaxNewSizePercent", hint = "Max young generation as % of heap. Aikar: 40.",
            value = g1.maxNewSizePercent.toFloat(), valueRange = 5f..90f, steps = 16,
            display = "${g1.maxNewSizePercent}%",
        ) { onChange(g1.copy(maxNewSizePercent = it.toInt())) }
        SliderField(
            label = "InitiatingHeapOccupancyPercent",
            hint = "When mixed GC starts. Aikar: 15 (eager). Stock: 45.",
            value = g1.initiatingHeapOccupancyPercent.toFloat(), valueRange = 5f..90f, steps = 16,
            display = "${g1.initiatingHeapOccupancyPercent}%",
        ) { onChange(g1.copy(initiatingHeapOccupancyPercent = it.toInt())) }
        ToggleField(
            label = "ParallelRefProcEnabled",
            hint = "Process references in parallel. Pure win on multi-core.",
            checked = g1.parallelRefProcEnabled,
        ) { onChange(g1.copy(parallelRefProcEnabled = it)) }
        ToggleField(
            label = "PerfDisableSharedMem",
            hint = "Skip /tmp/hsperfdata. Stops VisualVM but improves disk hygiene.",
            checked = g1.perfDisableSharedMem,
        ) { onChange(g1.copy(perfDisableSharedMem = it)) }
    }
}

@Composable
private fun ZgcTuningPanel(z: ZgcTuning, onChange: (ZgcTuning) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("ZGC tuning")
        ToggleField(
            label = "Generational ZGC",
            hint = "Java 21+ only. Splits heap into young/old. Significantly better than non-generational.",
            checked = z.generational,
        ) { onChange(z.copy(generational = it)) }
    }
}

@Composable
private fun ShenandoahTuningPanel(s: ShenandoahTuning, onChange: (ShenandoahTuning) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Shenandoah heuristic")
        ShenandoahTuning.Heuristic.entries.forEach { heuristic ->
            val hint = when (heuristic) {
                ShenandoahTuning.Heuristic.Adaptive -> "Default. Balances pause vs throughput."
                ShenandoahTuning.Heuristic.Static -> "Trigger collection at fixed thresholds."
                ShenandoahTuning.Heuristic.Compact -> "Aggressive compaction. Better at memory reclaim."
                ShenandoahTuning.Heuristic.Aggressive -> "Continuous collection. High throughput cost."
            }
            GcOption(heuristic.name, hint,
                selected = s.mode == heuristic) { onChange(s.copy(mode = heuristic)) }
        }
    }
}

// ─── Tab: AppCDS ──────────────────────────────────────────────────────────

@Composable
private fun CdsTabContent(config: JvmConfig, onChange: (JvmConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Application Class Data Sharing")
        Text(
            "Cache the loaded class metadata across launches. For 200+ mod packs, " +
                "saves 1-3 seconds on every cold start after the first.",
            color = CelestiaTheme.colors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))

        CdsConfig.Mode.entries.forEach { mode ->
            val (label, hint) = when (mode) {
                CdsConfig.Mode.Disabled -> "Disabled" to "No CDS. Default."
                CdsConfig.Mode.AutoArchive -> "Auto-archive (Java 19+)" to
                    "JVM auto-manages the archive at exit. No path needed."
                CdsConfig.Mode.ArchiveAtExit -> "Archive at exit" to
                    "Write archive to your specified path on shutdown."
                CdsConfig.Mode.UseArchive -> "Use existing archive" to
                    "Read pre-built archive from your specified path."
            }
            GcOption(label, hint, selected = config.cds.mode == mode) {
                onChange(config.copy(cds = config.cds.copy(mode = mode)))
            }
        }

        if (config.cds.mode in listOf(CdsConfig.Mode.ArchiveAtExit, CdsConfig.Mode.UseArchive)) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = config.cds.archivePath ?: "",
                onValueChange = { onChange(config.copy(cds = config.cds.copy(archivePath = it.ifBlank { null }))) },
                label = { Text("Archive path") },
                placeholder = { Text("/path/to/aura.jsa") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ─── Tab: JIT ─────────────────────────────────────────────────────────────

@Composable
private fun JitTabContent(config: JvmConfig, onChange: (JvmConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("JIT compiler")
        ToggleField(
            label = "Tiered compilation",
            hint = "On = warm-up via interpreter then C1 then C2 (default). Off = C2 only, slower start.",
            checked = config.jit.tieredCompilation,
        ) { onChange(config.copy(jit = config.jit.copy(tieredCompilation = it))) }

        Spacer(Modifier.height(4.dp))

        IntInput(
            label = "ReservedCodeCacheSize (MB)",
            hint = "Size of JIT-compiled code cache. JVM default is 240. Modded MC may benefit from 512+.",
            value = config.jit.codeCacheMb,
            placeholder = "240",
        ) { onChange(config.copy(jit = config.jit.copy(codeCacheMb = it))) }
    }
}

// ─── Tab: Performance / OS ────────────────────────────────────────────────

@Composable
private fun PerfTabContent(config: JvmConfig, onChange: (JvmConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Performance & OS-level flags")
        ToggleField(
            label = "AlwaysPreTouch",
            hint = "Touch every heap page at startup. Slower start, more consistent runtime.",
            checked = config.perf.alwaysPreTouch,
        ) { onChange(config.copy(perf = config.perf.copy(alwaysPreTouch = it))) }
        ToggleField(
            label = "DisableExplicitGC",
            hint = "Make System.gc() a no-op. Some legacy mods abuse it. Almost always a win.",
            checked = config.perf.disableExplicitGc,
        ) { onChange(config.copy(perf = config.perf.copy(disableExplicitGc = it))) }
        ToggleField(
            label = "UseLargePages (Linux only)",
            hint = "Requires hugepages pre-allocated via sysctl. ~2-5% perf gain when set up.",
            checked = config.perf.useLargePages,
        ) { onChange(config.copy(perf = config.perf.copy(useLargePages = it))) }
        ToggleField(
            label = "UseTransparentHugePages (Linux only)",
            hint = "Easier than UseLargePages. Adds latency spikes during defrag. Trade-off.",
            checked = config.perf.useTransparentHugePages,
        ) { onChange(config.perf.copy(useTransparentHugePages = it).let { config.copy(perf = it) }) }
        ToggleField(
            label = "UseNUMA",
            hint = "NUMA-aware allocation. Only useful on multi-socket systems.",
            checked = config.perf.numa,
        ) { onChange(config.copy(perf = config.perf.copy(numa = it))) }
        ToggleField(
            label = "HeapDumpOnOutOfMemoryError",
            hint = "Write a heap dump on OOM. Crucial for diagnostics.",
            checked = config.perf.heapDumpOnOom,
        ) { onChange(config.copy(perf = config.perf.copy(heapDumpOnOom = it))) }
        ToggleField(
            label = "ExitOnOutOfMemoryError",
            hint = "Exit on OOM instead of trying to limp along. Prevents zombie game state.",
            checked = config.perf.exitOnOom,
        ) { onChange(config.copy(perf = config.perf.copy(exitOnOom = it))) }
    }
}

// ─── Tab: JFR ─────────────────────────────────────────────────────────────

@Composable
private fun JfrTabContent(config: JvmConfig, onChange: (JvmConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Java Flight Recorder")
        Text(
            "Records JVM internals (allocations, GC, threads, locks). Open the resulting " +
                ".jfr in JDK Mission Control or IntelliJ for analysis.",
            color = CelestiaTheme.colors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        ToggleField(
            label = "Enable JFR recording",
            hint = "Default settings = ~1% overhead. Profile settings = ~5%, captures method-level.",
            checked = config.jfr.enabled,
        ) { onChange(config.copy(jfr = config.jfr.copy(enabled = it))) }

        if (config.jfr.enabled) {
            IntInput(
                label = "Duration (minutes)",
                hint = "How long the recording runs.",
                value = config.jfr.durationMinutes,
                placeholder = "60",
            ) {
                onChange(config.copy(jfr = config.jfr.copy(durationMinutes = it ?: 60)))
            }
            SectionHeader("Settings preset")
            JfrConfig.SettingsPreset.entries.forEach { preset ->
                val hint = when (preset) {
                    JfrConfig.SettingsPreset.Default -> "Low overhead, suitable for normal play."
                    JfrConfig.SettingsPreset.Profile -> "Method-level profiling. ~5% overhead."
                }
                GcOption(preset.name, hint, selected = config.jfr.settings == preset) {
                    onChange(config.copy(jfr = config.jfr.copy(settings = preset)))
                }
            }
            OutlinedTextField(
                value = config.jfr.outputPath ?: "",
                onValueChange = { onChange(config.copy(jfr = config.jfr.copy(outputPath = it.ifBlank { null }))) },
                label = { Text("Output .jfr path (optional)") },
                placeholder = { Text("/path/to/recording.jfr") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ─── Tab: Custom passthrough ──────────────────────────────────────────────

@Composable
private fun CustomTabContent(config: JvmConfig, onChange: (JvmConfig) -> Unit) {
    var text by remember(config.custom) { mutableStateOf(config.custom.joinToString(" ")) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Custom passthrough")
        Text(
            "Extra flags appended verbatim. Use for one-off experiments or vendor-specific " +
                "knobs not surfaced in the UI yet. Space-separated.",
            color = CelestiaTheme.colors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onChange(config.copy(custom = it.trim().split(Regex("\\s+")).filter { s -> s.isNotBlank() }))
            },
            label = { Text("Extra args") },
            placeholder = { Text("-Daura.weird.flag=42 -XX:+SomeNewThing") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
        )
    }
}

// ─── Live preview ─────────────────────────────────────────────────────────

@Composable
private fun ArgsPreviewBox(config: JvmConfig) {
    val args = remember(config) { config.toArgs() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Code, null, tint = CelestiaTheme.colors.primary,
                modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Preview (${args.size} flags)",
                color = CelestiaTheme.colors.textSecondary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp, max = 110.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CelestiaTheme.colors.background.copy(alpha = 0.6f))
                .border(1.dp, CelestiaTheme.colors.textSecondary.copy(alpha = 0.2f),
                    RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(
                args.joinToString(" "),
                color = CelestiaTheme.colors.textPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

// ─── Reusable widgets ─────────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = CelestiaTheme.colors.primary,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun SliderField(
    label: String,
    hint: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = CelestiaTheme.colors.textPrimary, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Text(display, color = CelestiaTheme.colors.primary, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = valueRange, steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = CelestiaTheme.colors.primary,
                activeTrackColor = CelestiaTheme.colors.primary,
            ),
        )
        Text(hint, color = CelestiaTheme.colors.textSecondary,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ToggleField(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = CelestiaTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
            Text(hint, color = CelestiaTheme.colors.textSecondary,
                style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CelestiaTheme.colors.primary,
                checkedTrackColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f),
            ),
        )
    }
}

@Composable
private fun IntInput(
    label: String,
    hint: String,
    value: Int?,
    placeholder: String,
    onChange: (Int?) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    Column {
        Text(label, color = CelestiaTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it.filter { c -> c.isDigit() }
                onChange(text.toIntOrNull())
            },
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(hint, color = CelestiaTheme.colors.textSecondary,
            style = MaterialTheme.typography.bodySmall)
    }
}
