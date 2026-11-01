package hivens.launcher.content

/**
 * The release channels an update may be drawn from, widest-first as a ladder.
 *
 * A mod that publishes releases must not be handed an alpha merely because the
 * alpha is newer, so the check asks for releases first and only widens for the
 * files nothing came back for. The same ladder Modrinth's own launcher walks,
 * and for the same reason: mods on a beta-only or alpha-only release train are
 * common enough that a release-only check reports "everything is current" for a
 * folder full of them.
 */
enum class ModUpdateChannel(val rungs: List<List<String>>) {
    Release(listOf(listOf("release"), listOf("beta"), listOf("alpha"))),
    Beta(listOf(listOf("release", "beta"), listOf("alpha"))),
    Alpha(listOf(listOf("release", "beta", "alpha"))),
}
