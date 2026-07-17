package hivens.launcher.update

import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.core.data.fileManifestOf
import hivens.launcher.util.sha1Of
import java.nio.file.Files
import java.nio.file.Path

/**
 * Scans the on-disk state of a pack instance into the `current` [FileManifest]
 * the [hivens.core.update.UpdateReconciler] needs. Only the [canonicalPaths]
 * (the union of the baseline's and target's declared files) are hashed -- never
 * the whole instance directory -- so worlds and other bulk user content are
 * never walked or hashed, and a file the reconcile ignores costs nothing.
 *
 * Optional-aware: an optional mod toggled off lives at `mods/<name>.disabled`,
 * so a `mods/<name>` path resolves to whichever variant is present. Both hold
 * identical bytes, so the recorded sha1 is the mod's either way and the reconcile
 * sees a disabled optional as present at its canonical path -- not "missing",
 * which would re-add it enabled.
 */
fun scanInstanceState(clientDir: Path, canonicalPaths: Set<String>): FileManifest {
    val root = clientDir.normalize()
    val entries = LinkedHashMap<String, FileData>()
    for (path in canonicalPaths) {
        val onDisk = locateOnDisk(root, path) ?: continue
        entries[path] = FileData(sha1 = sha1Of(onDisk), size = Files.size(onDisk))
    }
    return fileManifestOf(entries)
}

/**
 * Resolves [path] under [root] to the file actually on disk, or null if absent:
 * the canonical location first, then the `.disabled` variant for a `mods/` path.
 * Lexically confines the result to [root]; a baseline/manifest path that escapes
 * via `..` is skipped rather than read from outside the instance.
 */
private fun locateOnDisk(root: Path, path: String): Path? {
    val canonical = root.resolve(path).normalize()
    if (!canonical.startsWith(root)) return null
    if (Files.isRegularFile(canonical)) return canonical
    if (path.startsWith("mods/")) {
        val disabled = root.resolve("$path.disabled").normalize()
        if (disabled.startsWith(root) && Files.isRegularFile(disabled)) return disabled
    }
    return null
}
