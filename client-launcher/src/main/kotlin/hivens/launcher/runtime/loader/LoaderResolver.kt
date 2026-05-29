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
    /**
     * A file already on local disk (a modern installer's output in the loader
     * cache) for the provisioner to copy into the shared root. Preferred over
     * [bundled] for large artifacts (the patched client) so jar bytes never
     * sit in memory. Takes precedence over [bundled] and [url] when set.
     */
    val localFile: Path? = null,
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
/**
 * A file to copy into the shared libraries root that is NOT a classpath entry.
 * Modern Forge/NeoForge install processors emit jars (the SRG/slim/extra client,
 * the neoforge universal/client) that the version json does NOT list as
 * libraries -- FML's own locator finds them by path under `libraryDirectory` at
 * runtime. They must exist on disk there, but must stay OFF `-cp` (the minecraft
 * classes would otherwise load twice, in the system layer and FML's game layer).
 */
data class PlaceOnlyFile(
    val relPath: String,
    val source: Path,
)

data class LoaderProfile(
    val libraries: List<LibrarySpec>,
    val mainClass: String,
    val jvmArgs: List<String> = emptyList(),
    val gameArgs: List<String> = emptyList(),
    /**
     * Files to materialise in the shared root without adding them to the
     * classpath -- see [PlaceOnlyFile]. Empty for loaders with no install step.
     */
    val placeOnlyFiles: List<PlaceOnlyFile> = emptyList(),
    /**
     * True when this is a modern `inheritsFrom` overlay (Forge 1.13+ /
     * NeoForge): the launch needs vanilla's jvm/game args (the `--add-opens`
     * macros, `-cp ${classpath}`, ...) PREPENDED to these. Launchwrapper /
     * Knot loaders (Forge <=1.12.2, Fabric, Quilt) build their command from
     * the legacy path and set this false.
     */
    val inheritsVanillaArguments: Boolean = false,
    /**
     * The Java major this loader requires (e.g. Cleanroom declares 25). Null
     * means "no override -- inherit the vanilla MC's declared Java." Loaders
     * that don't change Java requirements (Forge legacy/modern, NeoForge,
     * Fabric, Quilt today) leave this null.
     */
    val javaMajor: Int? = null,
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
    /**
     * Platform-native jars (lwjgl etc.) for the host, resolved from the same
     * manifest as [libraries] -- so the natives extracted into an instance
     * always match the LWJGL version on the classpath. A loader overlay adds
     * none; this is the vanilla base's set.
     */
    val natives: List<Path> = emptyList(),
    /**
     * Declared Java major for this (MC version, loader): loader override
     * ([LoaderProfile.javaMajor]) wins over Mojang's per-version `javaVersion`
     * declaration, which wins over the launcher's heuristic. Null when nothing
     * declares it (legacy MC pre-1.17 + no loader override) -- the launcher
     * falls back to [hivens.core.api.interfaces.IJavaManager.detectJavaVersion].
     * This is what should drive JDK provisioning, NOT the MC version alone --
     * same MC, different loaders can need different Java majors (Cleanroom-
     * 1.12.2 wants 25, legacy-Forge-1.12.2 wants 8).
     */
    val javaMajor: Int? = null,
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
 * collision -- Forge ships its own asm / launchwrapper that must replace
 * vanilla's older copies. Preserves a stable order (base first, then loader-only
 * additions); the final `-cp` ordering (bootstrap jars first) is applied by the
 * command builder.
 *
 * The dedup key is `group:artifact:classifier`, NOT bare `group:artifact`:
 * modern Minecraft lists a library's base jar and its natives jar as two
 * separate entries with the same group:artifact but different classifiers
 * (`org.lwjgl:lwjgl:3.3.3` + `org.lwjgl:lwjgl:3.3.3:natives-linux`). Keying on
 * group:artifact alone makes the natives entry clobber the base, dropping
 * `org.lwjgl` from the module graph -- BootstrapLauncher then fails with
 * "Module org.lwjgl not found, required by org.lwjgl.natives".
 */
fun mergeLibraries(
    base: List<ResolvedLibrary>,
    overlay: List<ResolvedLibrary>,
): List<ResolvedLibrary> {
    fun key(coord: MavenCoord) = "${coord.groupArtifact}:${coord.classifier ?: ""}"
    val merged = LinkedHashMap<String, ResolvedLibrary>()
    for (lib in base) merged[key(lib.coord)] = lib
    for (lib in overlay) merged[key(lib.coord)] = lib
    return merged.values.toList()
}
