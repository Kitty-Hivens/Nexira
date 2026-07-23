package hivens.launcher.runtime

/**
 * A maven artifact coordinate parsed from the Gradle/Mojang notation
 * `group:artifact:version[:classifier][@extension]` that every loader's
 * library list uses.
 *
 * [groupArtifact] is the dedup key when a loader overlay is merged onto the
 * vanilla base: two entries with the same group+artifact are the same
 * dependency, and the overlay's version wins (Forge ships its own asm,
 * launchwrapper, etc. that must replace vanilla's). [relativePath] places the
 * jar under the shared libraries root in standard maven repo layout, which is
 * what both Mojang's `downloads.artifact.path` and a name-derived loader entry
 * resolve to.
 */
data class MavenCoord(
    val group: String,
    val artifact: String,
    val version: String,
    val classifier: String? = null,
    val extension: String = "jar",
) {
    val groupArtifact: String get() = "$group:$artifact"

    /**
     * The `natives-<platform>` token this coordinate targets, or null when it is
     * not a native jar. Two encodings appear in the wild and both resolve here:
     * a maven classifier (`org.lwjgl:lwjgl:3.4.1:natives-linux`, the Mojang /
     * Cleanroom form) and the platform baked into the artifact name
     * (`org.lwjgl:lwjgl-opengl-natives-linux:3.4.2`, the GTNH / lwjgl3ify form).
     * Lets one host filter cover both.
     */
    val nativeClassifier: String?
        get() {
            classifier?.let { if (it.startsWith("natives")) return it }
            val marker = artifact.indexOf("-natives-")
            return if (marker >= 0) artifact.substring(marker + 1) else null
        }

    val relativePath: String
        get() {
            val groupPath = group.replace('.', '/')
            val cls = classifier?.takeIf { it.isNotBlank() }?.let { "-$it" }.orEmpty()
            return "$groupPath/$artifact/$version/$artifact-$version$cls.$extension"
        }

    companion object {
        /**
         * Parses `group:artifact:version[:classifier][@extension]`. The
         * optional `@extension` suffix (e.g. Forge's
         * `de.oceanlabs.mcp:mcp_config:1.12.2@zip`) defaults to `jar`.
         */
        fun parse(name: String): MavenCoord {
            val at = name.indexOf('@')
            val extension = if (at >= 0) name.substring(at + 1).ifBlank { "jar" } else "jar"
            val core = if (at >= 0) name.substring(0, at) else name
            val parts = core.split(':')
            require(parts.size >= 3) { "invalid maven coordinate: '$name'" }
            return MavenCoord(
                group = parts[0],
                artifact = parts[1],
                version = parts[2],
                classifier = parts.getOrNull(3)?.takeIf { it.isNotBlank() },
                extension = extension,
            )
        }
    }
}
