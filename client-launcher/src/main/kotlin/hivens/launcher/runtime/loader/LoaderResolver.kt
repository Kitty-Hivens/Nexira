package hivens.launcher.runtime.loader

import hivens.launcher.runtime.MavenCoord
import java.nio.file.Path

/** Progress callback shared by the provisioner + loader downloads: (current, total, filename). */
typealias DownloadProgress = (current: Int, total: Int, filename: String) -> Unit

/**
 * One library a loader needs, as a download SPEC (not yet on disk). The
 * resolver produces these from the loader's profile; [RuntimeProvisioner]
 * downloads them into the shared libraries root. [sha1] is null when the
 * loader meta omits it (Quilt does for some libs) -- those download without
 * verification.
 */
class LibrarySpec(
    val coord: MavenCoord,
    val url: String? = null,
    val sha1: String? = null,
    val size: Long = 0,
    /**
     * Raw jar bytes when the artifact is bundled inside an installer rather
     * than published at a URL (the Forge universal jar lives in the installer's
     * `maven/` tree, not on Forge maven). When set, the provisioner writes these
     * instead of downloading [url].
     */
    val bundled: ByteArray? = null,
)

/**
 * A library resolved to its on-disk location, keyed by [coord] for dedup.
 */
data class ResolvedLibrary(
    val coord: MavenCoord,
    val path: Path,
)

/**
 * What a loader contributes on top of the vanilla base: extra libraries plus
 * the launch metadata (main class and any loader-specific JVM / game args,
 * e.g. Forge's `--tweakClass`). This is the loader's "version json overlay" --
 * Fabric/Quilt return it from their meta API directly; Forge/NeoForge derive
 * it from the installer's `version.json`.
 */
data class LoaderProfile(
    val libraries: List<LibrarySpec>,
    val mainClass: String,
    val jvmArgs: List<String> = emptyList(),
    val gameArgs: List<String> = emptyList(),
)

/**
 * The fully merged runtime handed to the launch path. [libraries] is the
 * explicit, deduped vanilla+loader set for THIS (mc, loader) -- NEVER a walk of
 * the shared libraries root, which holds many versions' jars at once. The
 * command builder orders these (bootstrap-first) and appends [clientJar] when
 * forming `-cp`.
 */
data class ResolvedRuntime(
    val libraries: List<ResolvedLibrary>,
    val clientJar: Path,
    val mainClass: String,
    val assetIndexId: String,
    val jvmArgs: List<String> = emptyList(),
    val gameArgs: List<String> = emptyList(),
)

/**
 * Resolves a single mod loader's overlay for a Minecraft + loader version.
 * Implementations fetch the loader's profile (network) and return library
 * specs + launch metadata; they do NOT download (that is the provisioner's
 * job, so caching/verification stay in one place). Vanilla has no resolver --
 * the absence of an overlay IS the vanilla case.
 */
interface LoaderResolver {
    /** Loader id as it appears in a manifest's `loader.name` (lowercase). */
    val loaderId: String

    suspend fun resolve(mcVersion: String, loaderVersion: String): LoaderProfile
}

/**
 * Looks up the [LoaderResolver] for a manifest's loader name. `vanilla`,
 * `none`, blank, and null all mean "no overlay" (pure vanilla).
 */
class LoaderRegistry(resolvers: List<LoaderResolver>) {
    private val byId: Map<String, LoaderResolver> = resolvers.associateBy { it.loaderId.lowercase() }

    fun resolverFor(loaderName: String?): LoaderResolver? {
        val id = loaderName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (id == "vanilla" || id == "none") return null
        return byId[id]
    }
}

/**
 * Merges a loader's libraries onto the vanilla base, the loader winning on a
 * `group:artifact` collision -- Forge ships its own asm / launchwrapper that
 * must replace vanilla's older copies. Preserves a stable order (base first,
 * then loader-only additions); the final `-cp` ordering (bootstrap jars first)
 * is applied by the command builder.
 */
fun mergeLibraries(
    base: List<ResolvedLibrary>,
    overlay: List<ResolvedLibrary>,
): List<ResolvedLibrary> {
    val byGroupArtifact = LinkedHashMap<String, ResolvedLibrary>()
    for (lib in base) byGroupArtifact[lib.coord.groupArtifact] = lib
    for (lib in overlay) byGroupArtifact[lib.coord.groupArtifact] = lib
    return byGroupArtifact.values.toList()
}
