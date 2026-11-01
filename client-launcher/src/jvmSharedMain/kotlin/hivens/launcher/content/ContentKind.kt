package hivens.launcher.content

/** What kind of thing sits in an instance folder: a mod, a resource pack or a shader pack. */
enum class ContentKind { Mod, ResourcePack, ShaderPack }

/**
 * The instance-relative folder a kind lives in. One answer, here: the scanner,
 * the manager, the updater and the Content tab each used to carry their own
 * copy of this `when`, which is three chances for a new kind to land in the
 * wrong folder.
 */
fun ContentKind.folderName(): String = when (this) {
    ContentKind.Mod          -> "mods"
    ContentKind.ResourcePack -> "resourcepacks"
    ContentKind.ShaderPack   -> "shaderpacks"
}
