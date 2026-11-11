package hivens.launcher.legacy

import java.nio.file.Path

/**
 * One client directory the retired SmartyCraft server path left under
 * `<dataDir>/clients/`.
 *
 * Nothing writes there any more, and nothing reads it except the default-skin
 * fallback, so every one of these is either content its owner still wants under
 * a name the launcher understands, or bytes to reclaim. Which of the two is the
 * owner's call, which is why this describes rather than decides.
 *
 * [mcVersion] and [loader] are what the tree SAYS, not what is true. A client
 * carries several signals and they disagree: one of them has a `fabricloader.log`
 * beside a `KotlinForForge.jar`, because something tried Fabric once and left a
 * log behind. So they are offered to the reader to confirm rather than acted on,
 * and either may be null when nothing in the tree answers.
 */
data class RetiredClient(
    /** The directory's own name, which is the SmartyCraft server id. */
    val name: String,
    val dir: Path,
    val sizeBytes: Long,
    val mcVersion: String?,
    val loader: String?,
    /** Jars directly under `mods/`, so a reader can tell a played pack from a stub. */
    val modCount: Int,
) {
    /** Whether this one can become a pack at all: adoption needs a Minecraft version. */
    val adoptable: Boolean get() = !mcVersion.isNullOrBlank()
}
