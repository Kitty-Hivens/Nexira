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
        val args = ZgcTuning.Defaults.toArgs(javaMajor = 21)
        assertTrue("-XX:+UnlockExperimentalVMOptions" in args)
        assertTrue("-XX:+ZGenerational" in args)
    }

    @Test
    fun `ZGC without generational omits the flag`() {
        val args = ZgcTuning(generational = false).toArgs(javaMajor = 21)
        assertFalse("-XX:+ZGenerational" in args)
    }

    @Test
    fun `ZGenerational is not emitted for a runtime that removed it`() {
        // JDK 24 dropped the flag: generational became the only ZGC mode and the
        // option is rejected outright ("Unrecognized VM option"), so a pack on 24+
        // would not start at all.
        for (major in listOf(ZgcTuning.ZGENERATIONAL_REMOVED_IN, 25, 26)) {
            val args = ZgcTuning.Defaults.toArgs(javaMajor = major)
            assertFalse("-XX:+ZGenerational" in args, "emitted for Java $major: $args")
            assertTrue("-XX:+UnlockExperimentalVMOptions" in args, "the unlock still belongs")
        }
        for (major in listOf(21, 22, 23)) {
            assertTrue("-XX:+ZGenerational" in ZgcTuning.Defaults.toArgs(javaMajor = major),
                "the flag still selects generational mode on Java $major")
        }
    }

    @Test
    fun `an unknown runtime omits ZGenerational rather than risk the launch`() {
        // Omitting costs a throughput mode on 21-23; emitting costs the launch on
        // 24+. With no declared major -- the SmartyCraft server path -- take the
        // side that still starts.
        assertFalse("-XX:+ZGenerational" in ZgcTuning.Defaults.toArgs(javaMajor = null))
    }

    // ─── Shenandoah ───────────────────────────────────────────────────────

    @Test
    fun `Shenandoah adaptive heuristic does not emit a heuristics flag`() {
        // Adaptive is the JVM default -- no point passing it explicitly
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
        // Large pages off by default -- needs OS setup
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
        val args = cfg.toArgs(javaMajor = 21)
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
    fun `no experimental G1 flag is emitted without the unlock that admits it`() {
        // Measured on JDK 25 and 26: these three are the experimental ones in the
        // Aikar set. Emitting any of them without -XX:+UnlockExperimentalVMOptions
        // makes the JVM refuse to start, and the launcher can only report exit 1.
        val experimental = listOf(
            "-XX:G1NewSizePercent=",
            "-XX:G1MaxNewSizePercent=",
            "-XX:G1MixedGCLiveThresholdPercent=",
        )
        val args = G1Tuning.AikarDefaults.copy(unlockExperimentalVMOptions = false).toArgs()

        for (flag in experimental) {
            assertTrue(args.none { it.startsWith(flag) }, "$flag survived without the unlock: $args")
        }
        // The rest of the tuning is not experimental and must still apply.
        assertTrue(args.any { it.startsWith("-XX:MaxGCPauseMillis=") })
        assertTrue(args.any { it.startsWith("-XX:G1ReservePercent=") })
        assertTrue(args.any { it.startsWith("-XX:MaxTenuringThreshold=") })
    }

    @Test
    fun `a config that reaches the builder without the unlock still round-trips`() {
        // The path that produced the unstartable set: pick a non-G1 GC, apply (the
        // stored args now carry no unlock token), reopen, pick G1, apply.
        val noUnlock = JvmConfig.fromArgs("-XX:+UseParallelGC -Xmx4G")
        val backToG1 = noUnlock.copy(gc = GcChoice.G1)

        val args = backToG1.toArgs()
        assertTrue(args.none { it.startsWith("-XX:G1NewSizePercent=") }, "unstartable arg set: $args")
        assertEquals(args, JvmConfig.fromArgs(args.joinToString(" ")).toArgs())
    }

    @Test
    fun `preset ids are unique`() {
        val ids = JvmArgsPresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "Duplicate preset ids: $ids")
    }

    // ─── fromArgs round-trip (seeds the editor from stored args) ──────────

    @Test
    fun `every preset round-trips through fromArgs exactly`() {
        // fromArgs(preset.toArgString()).toArgs() must reproduce the preset's args
        // token-for-token -- otherwise seeding the editor from stored args would
        // silently mutate them on the next Apply.
        for (preset in JvmArgsPresets.all) {
            val original = preset.config.toArgs()
            val rebuilt = JvmConfig.fromArgs(preset.config.toArgString()).toArgs()
            assertEquals(original, rebuilt, "Preset ${preset.id} did not round-trip")
        }
    }

    @Test
    fun `passthrough flags survive a round-trip verbatim at the end`() {
        // The bug this fixes: a custom -D flag was wiped when the editor reseeded
        // from the default preset instead of the stored args.
        val stored = JvmArgsPresets.Aikar.config.toArgString() +
            " -Dcustomskinloader.ignorePatchFailure=true -Xss2M"
        val rebuilt = JvmConfig.fromArgs(stored)
        // Both unknown flags land in custom, in order, and re-emit at the tail.
        assertEquals(listOf("-Dcustomskinloader.ignorePatchFailure=true", "-Xss2M"), rebuilt.custom)
        val out = rebuilt.toArgs()
        assertEquals("-Xss2M", out.last())
        assertEquals("-Dcustomskinloader.ignorePatchFailure=true", out[out.size - 2])
        // ...and the Aikar recipe is still intact ahead of them.
        for (flag in JvmArgsPresets.Aikar.config.toArgs()) {
            assertTrue(flag in out, "Aikar flag $flag lost when custom flags were present")
        }
    }

    @Test
    fun `a GC-mismatched tuning flag is kept as passthrough not dropped`() {
        // A G1 knob under ZGC is not modelled by the Z path, so it must survive in
        // custom rather than being silently eaten by the non-matching GC.
        val rebuilt = JvmConfig.fromArgs("-XX:+UseZGC -XX:+ZGenerational -XX:MaxGCPauseMillis=999 -Dx=1")
        assertEquals(GcChoice.Z, rebuilt.gc)
        assertTrue("-XX:MaxGCPauseMillis=999" in rebuilt.custom)
        assertTrue("-Dx=1" in rebuilt.custom)
        val out = rebuilt.toArgs(javaMajor = 21)
        assertTrue("-XX:MaxGCPauseMillis=999" in out)
        assertTrue("-XX:+ZGenerational" in out)
    }

    @Test
    fun `blank input yields the default config`() {
        assertEquals(JvmConfig().toArgs(), JvmConfig.fromArgs("   ").toArgs())
    }

    @Test
    fun `custom knob values are parsed back into structured fields`() {
        // A tweaked preset (lowered pause target) must reflect in the G1 field,
        // not leak into custom.
        val stored = JvmArgsPresets.Aikar.config.copy(
            g1 = G1Tuning.AikarDefaults.copy(maxPauseMs = 100),
        ).toArgString()
        val rebuilt = JvmConfig.fromArgs(stored)
        assertEquals(100, rebuilt.g1.maxPauseMs)
        assertTrue(rebuilt.custom.isEmpty(), "no tokens should spill into custom")
    }
}
