package hivens.core.jvm

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val cdsLog = LoggerFactory.getLogger("hivens.core.jvm.CdsConfig")

/**
 * Pure-data model for the experimental JVM-args builder UI. Represents
 * user choices for tuning the game JVM -- GC algorithm, G1 / Z /
 * Shenandoah tuning, AppCDS, JIT, performance flags, JFR -- and emits
 * a list of `-XX:` / `-X` arguments via [toArgs]. Result drops into
 * [hivens.core.data.InstanceProfile.jvmArgs] (space-joined) so the
 * existing launch path picks it up unchanged.
 *
 * Liberica ships JFR, ZGC, Shenandoah, AppCDS, and large-page support
 * unrestricted -- the full surface is fair game; no Oracle
 * commercial-feature lockouts.
 *
 * The model is intentionally dumb: no validation, no platform-specific
 * gating. UI layer surfaces "Linux only" hints and filters presets
 * needing a JDK newer than the configured runtime.
 */
@Serializable
data class JvmConfig(
    val gc: GcChoice = GcChoice.G1,
    val g1: G1Tuning = G1Tuning.AikarDefaults,
    val zgc: ZgcTuning = ZgcTuning.Defaults,
    val shenandoah: ShenandoahTuning = ShenandoahTuning.Defaults,
    val cds: CdsConfig = CdsConfig.Disabled,
    val jit: JitConfig = JitConfig.Defaults,
    val perf: PerfFlags = PerfFlags.AikarDefaults,
    val jfr: JfrConfig = JfrConfig.Disabled,
    /** Power-user passthrough -- extra flags appended verbatim. */
    val custom: List<String> = emptyList(),
) {
    fun toArgs(): List<String> = buildList {
        addAll(gc.toArgs())
        when (gc) {
            GcChoice.G1 -> addAll(g1.toArgs())
            GcChoice.Z -> addAll(zgc.toArgs())
            GcChoice.Shenandoah -> addAll(shenandoah.toArgs())
            GcChoice.Parallel, GcChoice.Serial -> Unit
        }
        addAll(cds.toArgs())
        addAll(jit.toArgs())
        addAll(perf.toArgs())
        addAll(jfr.toArgs())
        addAll(custom)
    }

    fun toArgString(): String = toArgs().joinToString(" ")
}

// ─── GC algorithm choice ─────────────────────────────────────────────────

@Serializable
enum class GcChoice {
    /** G1GC -- recommended default for modded MC at 4-32 GB heaps. */
    G1,

    /** ParallelGC -- high throughput, latency-tolerant; old default. */
    Parallel,

    /**
     * ZGC -- sub-millisecond pauses, scales to TB heap. Stable since
     * Java 15; generational variant since Java 21 (`-XX:+ZGenerational`).
     */
    Z,

    /**
     * Shenandoah -- low-pause concurrent collector from Red Hat /
     * OpenJDK. Liberica ships it; Oracle JDK does not.
     */
    Shenandoah,

    /** SerialGC -- single-threaded, only useful for tiny heaps (< 1 GB). */
    Serial;

    fun toArgs(): List<String> = when (this) {
        G1 -> listOf("-XX:+UseG1GC")
        Parallel -> listOf("-XX:+UseParallelGC")
        Z -> listOf("-XX:+UnlockExperimentalVMOptions", "-XX:+UseZGC")
        Shenandoah -> listOf("-XX:+UnlockExperimentalVMOptions", "-XX:+UseShenandoahGC")
        Serial -> listOf("-XX:+UseSerialGC")
    }
}

// ─── G1GC tuning ─────────────────────────────────────────────────────────

/**
 * G1GC tuning knobs. Defaults match Aikar's flags -- the canonical
 * Paper / Forge server tuning that has also been the de-facto modded
 * MC client recipe for years.
 *
 * Reference: https://docs.papermc.io/paper/aikars-flags
 */
@Serializable
data class G1Tuning(
    val maxPauseMs: Int = 200,
    val regionSizeMb: Int = 8,
    val newSizePercent: Int = 30,
    val maxNewSizePercent: Int = 40,
    val reservePercent: Int = 20,
    val heapWastePercent: Int = 5,
    val mixedGCCountTarget: Int = 4,
    val initiatingHeapOccupancyPercent: Int = 15,
    val mixedGCLiveThresholdPercent: Int = 90,
    val rsetUpdatingPauseTimePercent: Int = 5,
    val survivorRatio: Int = 32,
    val maxTenuringThreshold: Int = 1,
    val unlockExperimentalVMOptions: Boolean = true,
    val parallelRefProcEnabled: Boolean = true,
    val perfDisableSharedMem: Boolean = true,
) {
    fun toArgs(): List<String> = buildList {
        if (unlockExperimentalVMOptions) add("-XX:+UnlockExperimentalVMOptions")
        if (parallelRefProcEnabled) add("-XX:+ParallelRefProcEnabled")
        add("-XX:MaxGCPauseMillis=$maxPauseMs")
        add("-XX:G1HeapRegionSize=${regionSizeMb}M")
        add("-XX:G1NewSizePercent=$newSizePercent")
        add("-XX:G1MaxNewSizePercent=$maxNewSizePercent")
        add("-XX:G1ReservePercent=$reservePercent")
        add("-XX:G1HeapWastePercent=$heapWastePercent")
        add("-XX:G1MixedGCCountTarget=$mixedGCCountTarget")
        add("-XX:InitiatingHeapOccupancyPercent=$initiatingHeapOccupancyPercent")
        add("-XX:G1MixedGCLiveThresholdPercent=$mixedGCLiveThresholdPercent")
        add("-XX:G1RSetUpdatingPauseTimePercent=$rsetUpdatingPauseTimePercent")
        add("-XX:SurvivorRatio=$survivorRatio")
        add("-XX:MaxTenuringThreshold=$maxTenuringThreshold")
        if (perfDisableSharedMem) add("-XX:+PerfDisableSharedMem")
    }

    companion object {
        /** Aikar's flags -- the canonical modded-MC G1 recipe. */
        val AikarDefaults = G1Tuning()

        /** Stock G1 -- JVM defaults with no overrides; A/B baseline against Aikar's. */
        val Stock = G1Tuning(
            regionSizeMb = 4,
            newSizePercent = 20,
            maxNewSizePercent = 40,
            reservePercent = 10,
            mixedGCCountTarget = 8,
            initiatingHeapOccupancyPercent = 45,
            mixedGCLiveThresholdPercent = 65,
            rsetUpdatingPauseTimePercent = 10,
            survivorRatio = 8,
            maxTenuringThreshold = 15,
            parallelRefProcEnabled = false,
            perfDisableSharedMem = false,
        )
    }
}

// ─── ZGC tuning ──────────────────────────────────────────────────────────

@Serializable
data class ZgcTuning(
    val unlockExperimentalVMOptions: Boolean = true,
    /**
     * Generational ZGC, available since Java 21. Splits heap into
     * young / old like G1, dramatically improving throughput over
     * single-generation ZGC. Should always be on for Java 21+.
     */
    val generational: Boolean = true,
) {
    fun toArgs(): List<String> = buildList {
        if (unlockExperimentalVMOptions) add("-XX:+UnlockExperimentalVMOptions")
        if (generational) add("-XX:+ZGenerational")
    }

    companion object {
        val Defaults = ZgcTuning()
    }
}

// ─── Shenandoah tuning ──────────────────────────────────────────────────

@Serializable
data class ShenandoahTuning(
    val unlockExperimentalVMOptions: Boolean = true,
    val mode: Heuristic = Heuristic.Adaptive,
) {
    enum class Heuristic { Adaptive, Static, Compact, Aggressive }

    fun toArgs(): List<String> = buildList {
        if (unlockExperimentalVMOptions) add("-XX:+UnlockExperimentalVMOptions")
        if (mode != Heuristic.Adaptive) {
            add("-XX:ShenandoahGCHeuristics=${mode.name.lowercase()}")
        }
    }

    companion object {
        val Defaults = ShenandoahTuning()
    }
}

// ─── Class Data Sharing (AppCDS) ────────────────────────────────────────

/**
 * AppCDS speeds up cold-start by sharing the system + application
 * class archive across launches. For a 200+ mod modpack this saves
 * 1-3 seconds on every launch after the first.
 */
@Serializable
data class CdsConfig(
    val mode: Mode = Mode.Disabled,
    val archivePath: String? = null,
) {
    enum class Mode {
        Disabled,
        /** JDK 19+: dump archive at JVM exit, reuse on next launch automatically. */
        AutoArchive,
        /** Explicit: write archive to [archivePath] at exit. */
        ArchiveAtExit,
        /** Explicit: read archive from [archivePath] (requires prior dump). */
        UseArchive,
    }

    fun toArgs(): List<String> = when (mode) {
        Mode.Disabled -> emptyList()
        Mode.AutoArchive -> listOf("-XX:+AutoSharedArchiveAtExit")
        // archivePath-less paths silently produce no flag; warn so a
        // misconfigured preset doesn't pretend CDS is on. UI dialog is
        // the proper validation seam; a runtime safety net is cheap.
        Mode.ArchiveAtExit -> archivePath?.let { listOf("-XX:ArchiveClassesAtExit=$it") }
            ?: emptyList<String>().also { cdsLog.warn("CDS mode=ArchiveAtExit without archivePath -- producing no -XX flag") }
        Mode.UseArchive -> archivePath?.let { listOf("-XX:SharedArchiveFile=$it") }
            ?: emptyList<String>().also { cdsLog.warn("CDS mode=UseArchive without archivePath -- producing no -XX flag") }
    }

    companion object {
        val Disabled = CdsConfig()
    }
}

// ─── JIT tuning ──────────────────────────────────────────────────────────

@Serializable
data class JitConfig(
    val tieredCompilation: Boolean = true,
    /** Reserved JIT code cache (MB). Null = JVM default (240 MB). */
    val codeCacheMb: Int? = null,
    /** Initial code cache (MB). Null = JVM default. */
    val initialCodeCacheMb: Int? = null,
    /** Method invocations before tier-4 compile. Null = default (10000). */
    val compileThreshold: Int? = null,
) {
    fun toArgs(): List<String> = buildList {
        if (!tieredCompilation) add("-XX:-TieredCompilation")
        codeCacheMb?.let { add("-XX:ReservedCodeCacheSize=${it}M") }
        initialCodeCacheMb?.let { add("-XX:InitialCodeCacheSize=${it}M") }
        compileThreshold?.let { add("-XX:CompileThreshold=$it") }
    }

    companion object {
        val Defaults = JitConfig()
    }
}

// ─── Performance / OS-level flags ───────────────────────────────────────

@Serializable
data class PerfFlags(
    /** Touch every heap page at startup. Slightly slower start, more consistent runtime. */
    val alwaysPreTouch: Boolean = true,
    /**
     * Make `System.gc()` calls a no-op. Some legacy mods call this
     * every few seconds; suppressing the explicit GC is almost always
     * a win.
     */
    val disableExplicitGc: Boolean = true,
    /**
     * `-XX:+UseLargePages`. Linux only -- requires
     * `/proc/sys/vm/nr_hugepages` pre-allocated. ~2-5% on modded MC if
     * you set it up.
     */
    val useLargePages: Boolean = false,
    /**
     * `-XX:+UseTransparentHugePages`. Linux only -- uses the kernel's
     * THP feature instead of explicit hugepages. Easier setup than
     * [useLargePages] but adds latency spikes during defrag.
     */
    val useTransparentHugePages: Boolean = false,
    /** NUMA-aware allocation. Only useful on multi-socket systems. */
    val numa: Boolean = false,
    /** Heap dump on OOM into the working directory. Crucial for diagnostics. */
    val heapDumpOnOom: Boolean = true,
    /** Exit JVM on OutOfMemoryError instead of trying to limp along. */
    val exitOnOom: Boolean = true,
) {
    fun toArgs(): List<String> = buildList {
        if (alwaysPreTouch) add("-XX:+AlwaysPreTouch")
        if (disableExplicitGc) add("-XX:+DisableExplicitGC")
        if (useLargePages) add("-XX:+UseLargePages")
        if (useTransparentHugePages) {
            add("-XX:+UnlockDiagnosticVMOptions")
            add("-XX:+UseTransparentHugePages")
        }
        if (numa) add("-XX:+UseNUMA")
        if (heapDumpOnOom) add("-XX:+HeapDumpOnOutOfMemoryError")
        if (exitOnOom) add("-XX:+ExitOnOutOfMemoryError")
    }

    companion object {
        val AikarDefaults = PerfFlags()
        val Conservative = PerfFlags(alwaysPreTouch = false)
    }
}

// ─── JFR profiling ──────────────────────────────────────────────────────

/**
 * Java Flight Recorder -- open in OpenJDK / Liberica (Oracle-commercial
 * in older JDKs only). Drop the resulting `.jfr` into JDK Mission
 * Control or IntelliJ to inspect allocation hot spots, lock contention,
 * thread states.
 */
@Serializable
data class JfrConfig(
    val enabled: Boolean = false,
    val outputPath: String? = null,
    val durationMinutes: Int = 60,
    val settings: SettingsPreset = SettingsPreset.Default,
) {
    enum class SettingsPreset {
        /** Low-overhead default -- < 1% impact, fine for normal play. */
        Default,
        /** Higher-detail profile -- ~5% impact, captures method-level info. */
        Profile,
    }

    fun toArgs(): List<String> = buildList {
        if (!enabled) return@buildList
        val parts = mutableListOf(
            "duration=${durationMinutes}m",
            "settings=${settings.name.lowercase()}",
        )
        outputPath?.let { parts += "filename=$it" }
        add("-XX:StartFlightRecording=${parts.joinToString(",")}")
    }

    companion object {
        val Disabled = JfrConfig()
    }
}
