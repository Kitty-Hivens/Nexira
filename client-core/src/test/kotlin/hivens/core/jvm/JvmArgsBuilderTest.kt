package hivens.core.jvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmArgsBuilderTest {

    // ─── GC algorithm choice ──────────────────────────────────────────────

    @Test
    fun `each GC emits its canonical -XX flag`() {
        assertEquals(listOf("-XX:+UseG1GC"), GcChoice.G1.toArgs())
        assertEquals(listOf("-XX:+UseParallelGC"), GcChoice.Parallel.toArgs())
        assertEquals(listOf("-XX:+UseSerialGC"), GcChoice.Serial.toArgs())
        // Z and Shenandoah need the experimental options unlock
        assertTrue("-XX:+UseZGC" in GcChoice.Z.toArgs())
        assertTrue("-XX:+UnlockExperimentalVMOptions" in GcChoice.Z.toArgs())
        assertTrue("-XX:+UseShenandoahGC" in GcChoice.Shenandoah.toArgs())
        assertTrue("-XX:+UnlockExperimentalVMOptions" in GcChoice.Shenandoah.toArgs())
    }

    // ─── G1Tuning: Aikar's flags exact match ──────────────────────────────

    @Test
    fun `Aikar defaults emit the canonical Paper recipe`() {
        // The Paper docs list these exact flags as Aikar's recipe.
        // If anyone tweaks G1Tuning.AikarDefaults this test catches the
        // accidental drift.
        val args = G1Tuning.AikarDefaults.toArgs()

        val expected = listOf(
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+ParallelRefProcEnabled",
            "-XX:MaxGCPauseMillis=200",
            "-XX:G1HeapRegionSize=8M",
            "-XX:G1NewSizePercent=30",
            "-XX:G1MaxNewSizePercent=40",
            "-XX:G1ReservePercent=20",
            "-XX:G1HeapWastePercent=5",
            "-XX:G1MixedGCCountTarget=4",
            "-XX:InitiatingHeapOccupancyPercent=15",
            "-XX:G1MixedGCLiveThresholdPercent=90",
            "-XX:G1RSetUpdatingPauseTimePercent=5",
            "-XX:SurvivorRatio=32",
            "-XX:MaxTenuringThreshold=1",
            "-XX:+PerfDisableSharedMem",
        )
        assertEquals(expected, args)
    }

    @Test
    fun `G1 stock differs from Aikar's at the documented knobs`() {
        // Spot-check the differences between Stock and Aikar
        val stock = G1Tuning.Stock.toArgs()
        assertTrue("-XX:G1HeapRegionSize=4M" in stock)
        assertTrue("-XX:InitiatingHeapOccupancyPercent=45" in stock)
        // Stock disables the Aikar-only flags
        assertFalse("-XX:+ParallelRefProcEnabled" in stock)
        assertFalse("-XX:+PerfDisableSharedMem" in stock)
    }

    @Test
    fun `disabling unlockExperimental removes the flag from G1 output`() {
        val args = G1Tuning.AikarDefaults.copy(unlockExperimentalVMOptions = false).toArgs()
        assertFalse("-XX:+UnlockExperimentalVMOptions" in args)
    }

    // ─── ZGC ──────────────────────────────────────────────────────────────

    @Test
    fun `ZGC defaults emit generational mode and unlock options`() {
        val args = ZgcTuning.Defaults.toArgs()
        assertTrue("-XX:+UnlockExperimentalVMOptions" in args)
        assertTrue("-XX:+ZGenerational" in args)
    }

    @Test
    fun `ZGC without generational omits the flag`() {
        val args = ZgcTuning(generational = false).toArgs()
        assertFalse("-XX:+ZGenerational" in args)
    }

    // ─── Shenandoah ───────────────────────────────────────────────────────

    @Test
    fun `Shenandoah adaptive heuristic does not emit a heuristics flag`() {
        // Adaptive is the JVM default — no point passing it explicitly
        val args = ShenandoahTuning(mode = ShenandoahTuning.Heuristic.Adaptive).toArgs()
        assertFalse(args.any { it.startsWith("-XX:ShenandoahGCHeuristics=") })
    }

    @Test
    fun `Shenandoah non-adaptive heuristic IS emitted`() {
        val args = ShenandoahTuning(mode = ShenandoahTuning.Heuristic.Compact).toArgs()
        assertTrue("-XX:ShenandoahGCHeuristics=compact" in args)
    }

    // ─── AppCDS ───────────────────────────────────────────────────────────

    @Test
    fun `CDS disabled emits nothing`() {
        assertEquals(emptyList(), CdsConfig.Disabled.toArgs())
    }

    @Test
    fun `CDS auto-archive works without a path`() {
        val args = CdsConfig(mode = CdsConfig.Mode.AutoArchive).toArgs()
        assertEquals(listOf("-XX:+AutoSharedArchiveAtExit"), args)
    }

    @Test
    fun `CDS archive-at-exit requires a path`() {
        val withPath = CdsConfig(mode = CdsConfig.Mode.ArchiveAtExit, archivePath = "/tmp/cds.jsa").toArgs()
        assertEquals(listOf("-XX:ArchiveClassesAtExit=/tmp/cds.jsa"), withPath)

        // Without a path it's a no-op rather than crashing
        val noPath = CdsConfig(mode = CdsConfig.Mode.ArchiveAtExit).toArgs()
        assertEquals(emptyList(), noPath)
    }

    @Test
    fun `CDS use-archive requires a path`() {
        val withPath = CdsConfig(mode = CdsConfig.Mode.UseArchive, archivePath = "/tmp/cds.jsa").toArgs()
        assertEquals(listOf("-XX:SharedArchiveFile=/tmp/cds.jsa"), withPath)
    }

    // ─── JIT ──────────────────────────────────────────────────────────────

    @Test
    fun `JIT defaults emit no flags`() {
        // The whole point of "defaults" is "leave the JVM alone"
        assertEquals(emptyList(), JitConfig.Defaults.toArgs())
    }

    @Test
    fun `JIT codeCacheMb is converted to size suffix`() {
        val args = JitConfig(codeCacheMb = 512).toArgs()
        assertTrue("-XX:ReservedCodeCacheSize=512M" in args)
    }

    @Test
    fun `disabling tiered compilation emits the negative flag`() {
        val args = JitConfig(tieredCompilation = false).toArgs()
        assertTrue("-XX:-TieredCompilation" in args)
    }

    // ─── PerfFlags ────────────────────────────────────────────────────────

    @Test
    fun `Aikar perf defaults emit pretouch and explicit-gc disable`() {
        val args = PerfFlags.AikarDefaults.toArgs()
        assertTrue("-XX:+AlwaysPreTouch" in args)
        assertTrue("-XX:+DisableExplicitGC" in args)
        assertTrue("-XX:+HeapDumpOnOutOfMemoryError" in args)
        assertTrue("-XX:+ExitOnOutOfMemoryError" in args)
        // Large pages off by default — needs OS setup
        assertFalse("-XX:+UseLargePages" in args)
    }

    @Test
    fun `transparent huge pages requires the diagnostics unlock`() {
        val args = PerfFlags(useTransparentHugePages = true).toArgs()
        assertTrue("-XX:+UnlockDiagnosticVMOptions" in args)
        assertTrue("-XX:+UseTransparentHugePages" in args)
    }

    @Test
    fun `conservative perf disables pre-touch only`() {
        val args = PerfFlags.Conservative.toArgs()
        assertFalse("-XX:+AlwaysPreTouch" in args)
        // But still keeps the safety flags
        assertTrue("-XX:+DisableExplicitGC" in args)
    }

    // ─── JFR ──────────────────────────────────────────────────────────────

    @Test
    fun `JFR disabled emits nothing`() {
        assertEquals(emptyList(), JfrConfig.Disabled.toArgs())
    }

    @Test
    fun `JFR enabled emits StartFlightRecording with duration and settings`() {
        val args = JfrConfig(
            enabled = true,
            durationMinutes = 10,
            settings = JfrConfig.SettingsPreset.Profile,
        ).toArgs()
        val jfrArg = args.single { it.startsWith("-XX:StartFlightRecording=") }
        assertTrue("duration=10m" in jfrArg)
        assertTrue("settings=profile" in jfrArg)
        // No filename when path is null
        assertFalse("filename=" in jfrArg)
    }

    @Test
    fun `JFR enabled with output path includes filename`() {
        val args = JfrConfig(
            enabled = true,
            outputPath = "/tmp/run.jfr",
            settings = JfrConfig.SettingsPreset.Default,
        ).toArgs()
        val jfrArg = args.single { it.startsWith("-XX:StartFlightRecording=") }
        assertTrue("filename=/tmp/run.jfr" in jfrArg)
    }

    // ─── JvmConfig composition ────────────────────────────────────────────

    @Test
    fun `JvmConfig composes GC then per-GC then perf then jfr in order`() {
        val cfg = JvmConfig(
            gc = GcChoice.G1,
            g1 = G1Tuning.AikarDefaults,
            perf = PerfFlags.AikarDefaults,
        )
        val args = cfg.toArgs()
        // G1's UseG1GC must precede G1Tuning's MaxGCPauseMillis
        val useG1Idx = args.indexOf("-XX:+UseG1GC")
        val pauseIdx = args.indexOf("-XX:MaxGCPauseMillis=200")
        val pretouchIdx = args.indexOf("-XX:+AlwaysPreTouch")
        assertTrue(useG1Idx < pauseIdx, "GC choice must come before its tuning")
        assertTrue(pauseIdx < pretouchIdx, "GC tuning must come before perf flags")
    }

    @Test
    fun `non-G1 GC ignores G1 tuning`() {
        val cfg = JvmConfig(
            gc = GcChoice.Z,
            g1 = G1Tuning.AikarDefaults,  // present but should be ignored
        )
        val args = cfg.toArgs()
        assertFalse("-XX:G1HeapRegionSize=8M" in args)
        assertFalse("-XX:MaxGCPauseMillis=200" in args)
        assertTrue("-XX:+UseZGC" in args)
        assertTrue("-XX:+ZGenerational" in args)
    }

    @Test
    fun `custom passthrough is appended verbatim at the end`() {
        val cfg = JvmConfig(custom = listOf("-Daura.weird.flag=42", "-XX:+SomeNewThing"))
        val args = cfg.toArgs()
        assertEquals("-Daura.weird.flag=42", args[args.size - 2])
        assertEquals("-XX:+SomeNewThing", args.last())
    }

    @Test
    fun `toArgString joins with single spaces`() {
        val cfg = JvmConfig(gc = GcChoice.Serial, g1 = G1Tuning.Stock, perf = PerfFlags.Conservative)
        val joined = cfg.toArgString()
        assertFalse("  " in joined, "no double spaces")
        assertTrue("-XX:+UseSerialGC" in joined)
    }

    // ─── Presets sanity ───────────────────────────────────────────────────

    @Test
    fun `every preset produces a non-empty arg list`() {
        for (preset in JvmArgsPresets.all) {
            val args = preset.config.toArgs()
            assertTrue(args.isNotEmpty(), "Preset ${preset.id} produced no args")
        }
    }

    @Test
    fun `Aikar preset matches the standalone Aikar G1 tuning`() {
        // The "Aikar" preset's args must be a superset of pure G1 Aikar tuning
        val presetArgs = JvmArgsPresets.Aikar.config.toArgs()
        for (flag in G1Tuning.AikarDefaults.toArgs()) {
            assertTrue(flag in presetArgs, "Aikar preset missing $flag")
        }
    }

    @Test
    fun `ZGC preset declares Java 17 as minimum`() {
        assertEquals(17, JvmArgsPresets.ZgcLowLatency.minJavaVersion)
        assertTrue(JvmArgsPresets.ZgcLowLatency.minRecommendedHeapMb >= 8192)
    }

    @Test
    fun `default preset is in the all-list`() {
        assertTrue(JvmArgsPresets.default in JvmArgsPresets.all)
    }

    @Test
    fun `preset ids are unique`() {
        val ids = JvmArgsPresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "Duplicate preset ids: $ids")
    }
}
