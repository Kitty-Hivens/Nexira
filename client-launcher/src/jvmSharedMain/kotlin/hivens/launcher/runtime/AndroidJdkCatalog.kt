package hivens.launcher.runtime

/**
 * One Java build for Android, as the repository that builds them publishes it.
 *
 * [sha256] is pinned here, in the launcher, and not read from a file beside the
 * archive. A phone cannot run `java -version` to find out whether what it unpacked is
 * a working runtime, and a checksum fetched from the host that serves the archive
 * proves nothing the archive does not. The launcher and the builds are published by
 * the same people, so the launcher can afford to say which bytes it expects.
 */
data class AndroidJdkRelease(
    val major: Int,
    /** The `JAVA_VERSION` the runtime reports, which is also how an installed one is told from an older build. */
    val version: String,
    val url: String,
    val sha256: String,
) {
    init {
        require(SHA256.matches(sha256)) { "not a SHA-256 in lowercase hex: $sha256" }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * The Java builds a phone can be given. Nothing is pinned until a release has been
 * built, because a pin is the SHA-256 of an archive that exists.
 */
class AndroidJdkCatalog(releases: List<AndroidJdkRelease>) {

    private val byMajor = releases.associateBy { it.major }

    init {
        require(byMajor.size == releases.size) { "two releases for one Java major" }
    }

    val majors: Set<Int> get() = byMajor.keys

    fun forMajor(major: Int): AndroidJdkRelease? = byMajor[major]

    companion object {
        private const val RELEASES = "https://github.com/Kitty-Hivens/nexira-android-jdk/releases/download"

        /** Where a build of [version] is published, named the way the build script names its archive. */
        fun assetUrl(version: String): String = "$RELEASES/jdk-$version/jdk-$version-android-aarch64.zip"

        val PINNED = AndroidJdkCatalog(emptyList())
    }
}
