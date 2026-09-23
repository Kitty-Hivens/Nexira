package hivens.launcher.instance

/**
 * The directory name for a new instance: its name made filesystem-safe and
 * bounded, then its id whole.
 *
 * The id goes after the bound, never inside it. Every installer used to bound
 * `"$name-$id"` as one string, so a long name pushed the id out, part or all of
 * it, and a Cyrillic name reduced to underscores of the same length as any other:
 * two packs landed in one directory, the second install copied over the first,
 * and cancelling it deleted both. The bound keeps an absolute path well inside
 * PATH_MAX for a deep data directory.
 */
internal fun instanceDirName(name: String, instanceId: String): String =
    name.replace(UNSAFE, "_").take(NAME_BUDGET) + "-" + instanceId

/** Room for the name, leaving the separator and a 36-character id within the old bound of 96. */
private const val NAME_BUDGET = 59

private val UNSAFE = Regex("[^A-Za-z0-9._-]")
