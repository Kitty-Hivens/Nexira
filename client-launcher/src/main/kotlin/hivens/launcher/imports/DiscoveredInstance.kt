package hivens.launcher.imports

import java.nio.file.Path

/**
 * One importable instance found in a foreign launcher: an isolated game
 * directory ([gameDir]) with its own mods / config / saves. Modrinth, Prism and
 * FTB give one of these per instance directly; the vanilla launcher shares a
 * single `.minecraft`, so it yields one entry for that root plus one per profile
 * that overrides `gameDir`.
 *
 * Metadata is best-effort: [mcVersion] / [loader] / [loaderVersion] are null when
 * the source cannot read them cheaply at discovery time (they get resolved for
 * real in the import step). [modCount] is a jar count under `mods/`, purely for
 * the picker to show "48 mods".
 */
data class DiscoveredInstance(
    val launcher: ForeignLauncher,
    /** Stable within a launcher (directory name / profile key). */
    val id: String,
    val displayName: String,
    val gameDir: Path,
    val mcVersion: String? = null,
    val loader: String? = null,
    val loaderVersion: String? = null,
    val modCount: Int = 0,
)

/**
 * Discovers importable instances for one [launcher]. Discovery is read-only and
 * must never throw for a missing/foreign layout -- an absent launcher returns an
 * empty list. The copy/dedup import step is a separate phase layered on top of
 * this contract, so nothing here touches Nexira's own data dir.
 */
interface LauncherInstanceSource {
    val launcher: ForeignLauncher

    /** Instances found across every existing candidate root. Empty when the launcher is absent. */
    fun discover(): List<DiscoveredInstance>
}
