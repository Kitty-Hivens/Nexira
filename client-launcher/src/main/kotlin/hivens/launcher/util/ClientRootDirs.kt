package hivens.launcher.util

/**
 * Top-level directory names that a server-side manifest may legitimately
 * use as the first path segment of an entry. Used by manifest-path
 * normalisation in both `FileDownloadService.normalizePath` and
 * `ClasspathProvider.resolveSanitizedPath` so the two stay in sync about
 * which first segments are "known root dirs" (kept as-is) versus
 * "server-name prefix" (stripped).
 *
 * Prefix-match -- `libraries-1.12.2` qualifies because it starts with
 * `libraries`. Without this the launcher used to strip `config/foo.jar`
 * down to `foo.jar` (config is a valid root, not a server name) and
 * other classpath / filesystem layout drift between the two consumers.
 */
object ClientRootDirs {
    val ALL: Set<String> = setOf(
        "mods", "config", "bin", "assets", "libraries", "resources",
        "saves", "resourcepacks", "shaderpacks", "natives",
    )

    fun isKnown(firstSegment: String): Boolean =
        ALL.any { firstSegment.startsWith(it) }
}
