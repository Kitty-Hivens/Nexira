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
