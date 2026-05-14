package hivens.core.jvm

/**
 * A named, well-documented [JvmConfig] that the UI offers as a starting
 * point. Users can pick a preset and then customize individual fields.
 */
data class JvmPreset(
    val id: String,
    /** Human-readable label for the picker. */
    val displayName: String,
    /** One-paragraph description shown next to the picker — when to choose this. */
    val description: String,
    val config: JvmConfig,
    /**
     * Minimum recommended heap size, in MB. The UI uses this to warn when
     * the user picks a preset for a heap that's too small (e.g. ZGC at 2GB
     * would do nothing useful).
     */
    val minRecommendedHeapMb: Int = 0,
    /**
     * Minimum required Java major version. ZGC works since 15, Shenandoah
     * since 17, generational ZGC since 21. UI gates by this against the
     * runtime's actual version.
     */
    val minJavaVersion: Int = 8,
)

/**
 * Curated catalog of presets. Each one is documented so users learn
 * something about JVM tuning by reading the descriptions, not just by
 * blind copy-pasting flags from the wiki.
 *
 * Order matters — the UI lists them top to bottom and the first one is
 * the default selection.
 */
object JvmArgsPresets {

    /**
     * The Aikar's-flags recipe for modded MC. By far the most widely used
     * tuning for both modded clients and Paper/Forge servers. Excellent
     * starting point for any moderate-to-heavy modpack at 4-12GB heap.
     */
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

    /**
     * Larger heap-region size and bigger young generation for huge
     * modpacks (300+ mods, GTNH-class). The Aikar baseline assumes
     * "medium-heavy" — once you're past 12GB heap and have a thousand
     * loaded mod classes per chunk gen, the larger regions reduce
     * mixed-collection time.
     */
    val HeavyModded = JvmPreset(
        id = "heavy",
        displayName = "Heavy modded (GTNH-class, 12+ GB)",
        description = "Aikar's flags with 16 MB G1 regions and a larger young " +
            "generation. Targets huge modpacks with 300+ mods at 12-32 GB " +
            "heap. Use this for GTNH, Project Ozone Lite, big GregTech-style " +
            "packs. At smaller heaps, prefer Aikar's instead — bigger regions " +
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

    /**
     * Stock G1 — the JVM's default behavior with no Aikar overrides. Useful
     * as a comparison baseline or if you suspect Aikar's tuning is causing
     * problems on a specific pack.
     */
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

    /**
     * ZGC for very large heaps where pause-time dominates throughput. ZGC
     * pauses are sub-millisecond regardless of heap size, vs G1's tens of
     * ms — but ZGC costs ~10-15% throughput in exchange. Worth it once you
     * pass 16 GB heap, especially on modern Java (21+ with generational mode).
     */
    val ZgcLowLatency = JvmPreset(
        id = "zgc",
        displayName = "ZGC low-latency (Java 17+, 16+ GB heap)",
        description = "Sub-millisecond GC pauses regardless of heap size. " +
            "Trades ~10-15% throughput for consistent frame times on huge " +
            "heaps. Best at 16 GB+. Requires Java 17 (Java 21+ unlocks " +
            "generational ZGC, which is significantly better — auto-enabled).",
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

    /**
     * Shenandoah is conceptually similar to ZGC — concurrent, low-pause —
     * but is from the Red Hat / OpenJDK lineage rather than Oracle's. Liberica
     * ships it; Oracle JDK does not. Slightly different performance profile:
     * better at smaller heaps than ZGC, similar at huge heaps.
     */
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

    /**
     * Throughput-first preset using ParallelGC. Old-school batch behavior:
     * very high throughput between collections but stop-the-world pauses
     * scale with heap size. Almost never the right choice for interactive
     * MC — included for completeness and the rare "I just want benchmarks"
     * case.
     */
    val Throughput = JvmPreset(
        id = "parallel",
        displayName = "Throughput (ParallelGC, batch only)",
        description = "ParallelGC — maximum throughput with full stop-the-world " +
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

    /** All built-in presets, in suggested-display order. */
    val all: List<JvmPreset> = listOf(
        Aikar,
        HeavyModded,
        VanillaG1,
        ZgcLowLatency,
        ShenandoahLowLatency,
        Throughput,
    )

    /** Default selection for new users — Aikar covers the 95% case. */
    val default: JvmPreset = Aikar
}
