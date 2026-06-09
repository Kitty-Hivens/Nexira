package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * Update channel the user follows. Ordered from most stable to most
 * bleeding-edge; [ordinal] doubles as the cumulative rank -- a channel
 * surfaces its own tier plus every stabler one (Alpha sees alpha + beta +
 * release).
 *
 * - [Release], [Beta], [Alpha] are GitHub releases, classified by the tag's
 *   prerelease suffix ([classify]).
 * - [Dev], [Git] are NOT releases -- they build the launcher from the
 *   repository. `git` builds the stable branch / latest tag; `dev` also pulls
 *   the `dev` branch. Both need a local toolchain (git + JDK + gradle) and are
 *   gated behind the experimental master switch.
 */
@Serializable
enum class ReleaseChannel {
    Release,
    Beta,
    Alpha,
    Dev,
    Git;

    /** Build the launcher from source rather than downloading a release asset. */
    val isSourceBuild: Boolean get() = this == Dev || this == Git

    companion object {
        // git describe of a non-release checkout: commits beyond a tag
        // ("-<n>-g<sha>") -- the dirty flag is handled separately below.
        private val SOURCE_DESCRIBE = Regex("-\\d+-g[0-9a-f]+")

        /**
         * Channel a version string belongs to.
         *
         * A `git describe` that is not exactly on a tag -- commits ahead
         * (`-<n>-g<sha>`) or a dirty working tree (`-dirty`) -- is a build from
         * source, so it classifies as [Dev] rather than reading its base tag's
         * prerelease suffix. Clean tags map by suffix: no suffix -> [Release];
         * `-alpha*` -> [Alpha]; `-dev*` -> [Dev]; anything else prerelease
         * (`-beta`, `-rc1`, ...) -> [Beta].
         */
        fun classify(version: String): ReleaseChannel {
            val v = version.lowercase()
            if (v.endsWith("-dirty") || SOURCE_DESCRIBE.containsMatchIn(v)) return Dev
            val suffix = v.substringAfter('-', "")
            return when {
                suffix.isEmpty()           -> Release
                suffix.startsWith("alpha") -> Alpha
                suffix.startsWith("dev")   -> Dev
                else                       -> Beta
            }
        }
    }
}
