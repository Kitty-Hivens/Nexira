package hivens.update

import hivens.core.data.FileManifest
import hivens.core.data.flatten
import hivens.core.update.LauncherPatch
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createParentDirectories

/**
 * The on-the-wire delta for one release step, as a directory (packed into one small
 * archive as a release asset):
 *
 * ```
 * files.json            the target FileManifest (the update's remote baseline)
 * patches.json          path -> LauncherPatch for the changed files
 * patches/<path>.bsdiff bsdiff delta for each changed file (from the previous release)
 * full/<path>           whole copy of each ADDED file (no source to patch from)
 * ```
 *
 * Changed files ship only as patches (small); a whole copy is carried only for added
 * files. A client more than one release behind cannot apply these patches
 * ([DirectoryAssetSource.fetchFile] fails for the missing full copy), so the caller
 * falls back to a full re-provision -- CI never needs an N^2 patch matrix.
 */
class DirectoryAssetSource(private val bundleDir: Path) : AssetSource {
    override fun fetchFile(path: String, dest: Path) {
        val src = bundleDir.resolve("full").resolve(path)
        if (!Files.exists(src)) {
            throw FileNotFoundException("delta bundle has no whole copy of '$path' (client too far behind for these patches)")
        }
        dest.createParentDirectories()
        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun fetchPatch(patch: LauncherPatch, dest: Path) {
        dest.createParentDirectories()
        Files.copy(bundleDir.resolve("patches").resolve(patch.path + ".bsdiff"), dest, StandardCopyOption.REPLACE_EXISTING)
    }
}

data class DeltaBundle(
    val manifest: FileManifest,
    val patches: Map<String, LauncherPatch>,
    val source: AssetSource,
)

object DeltaBundles {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /** Read an extracted bundle directory. */
    fun read(bundleDir: Path): DeltaBundle {
        val manifest = LayoutManifest.read(bundleDir.resolve("files.json")) ?: FileManifest()
        val patchesFile = bundleDir.resolve("patches.json")
        val patches = if (Files.exists(patchesFile)) {
            json.decodeFromString<Map<String, LauncherPatch>>(Files.readString(patchesFile))
        } else emptyMap()
        return DeltaBundle(manifest, patches, DirectoryAssetSource(bundleDir))
    }

    /**
     * Produce the bundle for the step [oldLayout] -> [newLayout] into [out] (the CI
     * side; also drives the offline tests). Changed files get a bsdiff patch, added
     * files a whole copy, unchanged files nothing.
     */
    fun produce(oldLayout: Path, newLayout: Path, out: Path, excludes: Set<Path> = emptySet()) {
        Files.createDirectories(out)
        val oldFlat = LayoutManifest.scan(oldLayout, excludes).flatten()
        val newManifest = LayoutManifest.scan(newLayout, excludes)
        LayoutManifest.write(out.resolve("files.json"), newManifest)

        val patches = LinkedHashMap<String, LauncherPatch>()
        for ((path, newData) in newManifest.flatten()) {
            val oldData = oldFlat[path]
            when {
                oldData == null -> {
                    val dest = out.resolve("full").resolve(path)
                    dest.createParentDirectories()
                    Files.copy(newLayout.resolve(path), dest, StandardCopyOption.REPLACE_EXISTING)
                }
                !oldData.sha1.equals(newData.sha1, ignoreCase = true) -> {
                    val patchDest = out.resolve("patches").resolve("$path.bsdiff")
                    patchDest.createParentDirectories()
                    BinaryPatch.diff(oldLayout.resolve(path), newLayout.resolve(path), patchDest)
                    patches[path] = LauncherPatch(path, oldData.sha1, newData.sha1, Files.size(patchDest))
                }
                // unchanged: nothing ships
            }
        }
        Files.writeString(out.resolve("patches.json"), json.encodeToString(patches))
    }
}
