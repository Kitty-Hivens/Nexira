package hivens.core.jvm

/**
 * A named, well-documented [JvmConfig] that the UI offers as a starting
 * point. Users pick a preset, then customize individual fields.
 */
data class JvmPreset(
    val id: String,
    /** Human-readable label for the picker. */
    val displayName: String,
    /** One-paragraph description shown next to the picker -- when to choose this. */
    val description: String,
    val config: JvmConfig,
    /**
     * Minimum recommended heap in MB. UI warns when the user picks a
     * preset for a heap that is too small (e.g. ZGC at 2 GB does
     * nothing useful).
     */
    val minRecommendedHeapMb: Int = 0,
    /**
     * Minimum required Java major version. ZGC since 15, Shenandoah
     * since 17, generational ZGC since 21. UI gates by this against
     * the runtime's actual version.
     */
    val minJavaVersion: Int = 8,
)

/**
 * Curated catalog of presets. Each carries its own user-facing
 * [JvmPreset.description] so users learn something about JVM tuning by
 * reading the picker. Order matters: the UI lists top-to-bottom and
 * the first entry is the default selection.
 */
object JvmArgsPresets {

    val Aikar = JvmPreset(
        id = "aikar",
        displayName = "Aikar's flags (modded MC)",
        description = "Canonical G1GC recipe maintained by the PaperMC team. " +
            "Tuned for sustained modded-MC workloads with heaps 4-12 GB. " +
            "Reduces frame-time spikes from GC and is the de-facto default " +
            "for serious modded play.",
        config = JvmConfig(
            gc = GcChoice.G1,
            g1 = G1Tuning.AikarDefaults,
            perf = PerfFlags.AikarDefaults,
        ),
        minRecommendedHeapMb = 4096,
        minJavaVersion = 8,
    )

    val HeavyModded = JvmPreset(
        id = "heavy",
        displayName = "Heavy modded (GTNH-class, 12+ GB)",
        description = "Aikar's flags with 16 MB G1 regions and a larger young " +
            "generation. Targets huge modpacks with 300+ mods at 12-32 GB " +
            "heap. Use this for GTNH, Project Ozone Lite, big GregTech-style " +
            "packs. At smaller heaps, prefer Aikar's instead -- bigger regions " +
            "waste memory without paying off.",
        config = JvmConfig(
            gc = GcChoice.G1,
            g1 = G1Tuning.AikarDefaults.copy(
                regionSizeMb = 16,
                newSizePercent = 40,
                maxNewSizePercent = 50,
            ),
            perf = PerfFlags.AikarDefaults,
        ),
        minRecommendedHeapMb = 12_288,
        minJavaVersion = 8,
    )

    val VanillaG1 = JvmPreset(
        id = "vanilla-g1",
        displayName = "Vanilla / stock G1",
        description = "JVM defaults with no overrides. Equivalent to passing no " +
            "GC tuning at all. Suitable for vanilla Minecraft or a handful of " +
            "mods. Good A/B baseline against Aikar's when diagnosing issues.",
        config = JvmConfig(
            gc = GcChoice.G1,
            g1 = G1Tuning.Stock,
            perf = PerfFlags.Conservative,
        ),
        minRecommendedHeapMb = 0,
        minJavaVersion = 8,
    )

    val ZgcLowLatency = JvmPreset(
        id = "zgc",
        displayName = "ZGC low-latency (Java 17+, 16+ GB heap)",
        description = "Sub-millisecond GC pauses regardless of heap size. " +
            "Trades ~10-15% throughput for consistent frame times on huge " +
            "heaps. Best at 16 GB+. Requires Java 17 (Java 21+ unlocks " +
            "generational ZGC, which is significantly better -- auto-enabled).",
        config = JvmConfig(
            gc = GcChoice.Z,
            zgc = ZgcTuning(generational = true),
            perf = PerfFlags(
                alwaysPreTouch = true,
                disableExplicitGc = true,
                heapDumpOnOom = true,
                exitOnOom = true,
            ),
        ),
        minRecommendedHeapMb = 16_384,
        minJavaVersion = 17,
    )

    val ShenandoahLowLatency = JvmPreset(
        id = "shenandoah",
        displayName = "Shenandoah low-latency (Java 17+, OpenJDK/Liberica)",
        description = "Concurrent low-pause collector from the OpenJDK project. " +
            "Similar to ZGC but better at moderate heaps (8-16 GB). Requires " +
            "Java 17+ and an OpenJDK-derived JVM (Liberica qualifies; Oracle " +
            "JDK does not ship it).",
        config = JvmConfig(
            gc = GcChoice.Shenandoah,
            shenandoah = ShenandoahTuning(mode = ShenandoahTuning.Heuristic.Adaptive),
            perf = PerfFlags(
                alwaysPreTouch = true,
                disableExplicitGc = true,
                heapDumpOnOom = true,
                exitOnOom = true,
            ),
        ),
        minRecommendedHeapMb = 8_192,
        minJavaVersion = 17,
    )

    val Throughput = JvmPreset(
        id = "parallel",
        displayName = "Throughput (ParallelGC, batch only)",
        description = "ParallelGC -- maximum throughput with full stop-the-world " +
            "pauses. Pauses can be hundreds of ms on multi-GB heaps, which " +
            "feels awful in interactive MC. Almost never the right choice. " +
            "Included for benchmarking and academic comparison.",
        config = JvmConfig(
            gc = GcChoice.Parallel,
            perf = PerfFlags(
                alwaysPreTouch = true,
                disableExplicitGc = true,
                heapDumpOnOom = true,
                exitOnOom = true,
            ),
        ),
        minRecommendedHeapMb = 0,
        minJavaVersion = 8,
    )

    /** All built-in presets, in display order. */
    val all: List<JvmPreset> = listOf(
        Aikar,
        HeavyModded,
        VanillaG1,
        ZgcLowLatency,
        ShenandoahLowLatency,
        Throughput,
    )

    /** Default for new users -- Aikar covers the 95% case. */
    val default: JvmPreset = Aikar
}
