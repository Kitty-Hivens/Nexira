package hivens.core.update

/**
 * Release channel of a pack build, mirroring the mirror's `domain/version.rs`
 * vocabulary (Modrinth's `version_type`). Builds that predate the stored
 * channel field derive one from the version string; an unknown future wire
 * value falls back the same way rather than failing the decode.
 */
enum class VersionChannel(val wire: String) {
    Release("release"),
    Beta("beta"),
    Alpha("alpha");

    companion object {
        /** Resolve [wire] to a channel, deriving from [version] when absent or unknown. */
        fun of(wire: String?, version: String): VersionChannel =
            entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) } ?: legacyFor(version)

        /** Pre-channel manifests: the panel's `SNAPSHOT-` prefix meant beta, everything else release. */
        fun legacyFor(version: String): VersionChannel =
            if (version.startsWith("SNAPSHOT-")) Beta else Release
    }
}
