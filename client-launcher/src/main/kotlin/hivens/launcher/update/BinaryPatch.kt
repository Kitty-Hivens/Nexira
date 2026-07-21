package hivens.launcher.update

import io.sigpipe.jbsdiff.Diff
import io.sigpipe.jbsdiff.Patch
import java.nio.file.Files
import java.nio.file.Path

/**
 * Binary delta for launcher self-update files. The client only needs [apply]
 * (bspatch); [diff] produces a patch for CI / tests. bsdiff format, so the library
 * is swappable (zstd --patch-from is the alternative) without changing the
 * CI <-> client contract shape.
 *
 * Whole-file, in-memory: bsdiff holds old + new (+ patch) in memory, so a ~100 MB jar
 * patch needs a few hundred MB transiently -- fine for the launcher heap and CI, and
 * only the changed bytes land in the patch.
 */
object BinaryPatch {
    /** Reconstruct [out] from [oldFile] and the [patchFile] delta. */
    fun apply(oldFile: Path, patchFile: Path, out: Path) {
        val old = Files.readAllBytes(oldFile)
        val patch = Files.readAllBytes(patchFile)
        out.parent?.let { Files.createDirectories(it) }
        Files.newOutputStream(out).use { os -> Patch.patch(old, patch, os) }
    }

    /** Produce the [patchOut] delta from [oldFile] to [newFile] (CI / tests). */
    fun diff(oldFile: Path, newFile: Path, patchOut: Path) {
        val old = Files.readAllBytes(oldFile)
        val new = Files.readAllBytes(newFile)
        patchOut.parent?.let { Files.createDirectories(it) }
        Files.newOutputStream(patchOut).use { os -> Diff.diff(old, new, os) }
    }
}
