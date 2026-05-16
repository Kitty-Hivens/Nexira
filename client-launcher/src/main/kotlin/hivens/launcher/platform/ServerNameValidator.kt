package hivens.launcher.platform

/**
 * Whitelist for server identifiers (`assetDir` from the API + cache-file basenames).
 *
 * The launcher uses a server-supplied string as both the basename of a per-server
 * data directory (`<dataDir>/clients/<assetDir>/`) and the cache-file name
 * (`<dataDir>/manifest-cache/<assetDir>.json`). A malicious or malformed upstream
 * could send `../etc/passwd` and have either of those resolve outside the launcher
 * data dir. JVM `Path.resolve()` doesn't reject the traversal -- it composes the
 * absolute path verbatim -- so the gate has to live before resolution.
 *
 * Allowed characters: ASCII letters, digits, `_`, `-`, `.`. This matches every
 * SmartyCraft server id that has ever shipped (`Industrial`, `RPG`, `SkyBlock`,
 * `MagicRPG`, `Aura.v2`, etc.) and rules out path separators (`/`, `\`),
 * traversal sequences (`..`), whitespace, NULs, and anything else that could
 * trip OS-specific path parsing on Windows or macOS.
 *
 * Centralised here so [PlatformPaths.clientDir] and [hivens.launcher.ManifestCache]
 * apply the same rule. Adding a new caller? Validate at the boundary, not deeper.
 */
object ServerNameValidator {

    private val SAFE_NAME = Regex("^[A-Za-z0-9._-]+$")

    /**
     * @return [name] verbatim if it passes the whitelist.
     * @throws IllegalArgumentException with a redacted preview otherwise. The
     *         preview is truncated and brackets the rejected characters so a
     *         log reader can see what was sent without the full hostile string.
     */
    fun require(name: String): String {
        require(isValid(name)) {
            "Rejected server identifier: ${preview(name)}"
        }
        return name
    }

    /**
     * Cheap predicate version for callers that want to fall back rather than throw.
     *
     * Rejects:
     *   - the empty string,
     *   - the special path segments `.` and `..` (both pass the character
     *     regex but are filesystem traversal primitives),
     *   - any string with `..` anywhere (matches Windows alternate-stream-style
     *     constructs like `Industrial..\\evil` after the regex has separately
     *     filtered the slash -- defense in depth),
     *   - anything outside the character whitelist.
     */
    fun isValid(name: String): Boolean =
        SAFE_NAME.matches(name) && name != "." && name != ".." && !name.contains("..")

    private fun preview(name: String): String {
        val truncated = if (name.length > 48) name.take(48) + "…" else name
        return "<\"$truncated\">"
    }
}
