package hivens.launcher.runtime

/** One archive of a natives release, with the digest the launcher expects of it. */
data class AndroidNativesAsset(
    val name: String,
    val url: String,
    val sha256: String,
) {
    init {
        require(SHA256.matches(sha256)) { "not a SHA-256 in lowercase hex: $sha256" }
        require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "..") { "not a plain file name: $name" }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * The natives a phone is given for one LWJGL release: the libraries LWJGL itself builds for that release, and the ones
 * the host adds (the host core, the stand-in for GLFW, the GL layer), which are the same in every release.
 *
 * The digests are pinned here, in the launcher, for the reason the Java builds' are: a phone cannot run what it
 * unpacked to find out whether it works, and a checksum fetched from the host that serves the archive proves nothing
 * the archive does not.
 */
data class AndroidNativesRelease(
    val lwjglVersion: String,
    val assets: List<AndroidNativesAsset>,
) {
    init {
        require(assets.isNotEmpty()) { "a release with no archives" }
        require(assets.map { it.name }.toSet().size == assets.size) { "two archives with one name" }
    }
}

/**
 * The LWJGL releases a phone has natives for. A release is pinned once it exists, by `scripts/pin-android-natives.py`,
 * because a pin is the SHA-256 of an archive that exists.
 */
class AndroidNativesCatalog(releases: List<AndroidNativesRelease>) {

    private val byVersion = releases.associateBy { it.lwjglVersion }

    init {
        require(byVersion.size == releases.size) { "two releases for one LWJGL version" }
    }

    val versions: Set<String> get() = byVersion.keys

    fun forLwjgl(version: String): AndroidNativesRelease? = byVersion[version]

    companion object {
        private const val RELEASES = "https://github.com/Kitty-Hivens/nexira-android-lwjgl/releases/download"

        /** Where an archive of the release for [lwjglVersion] is published, named the way the build scripts name it. */
        fun assetUrl(lwjglVersion: String, name: String): String = "$RELEASES/lwjgl-$lwjglVersion/$name"

        val PINNED = AndroidNativesCatalog(ANDROID_NATIVES_PINS)
    }
}
