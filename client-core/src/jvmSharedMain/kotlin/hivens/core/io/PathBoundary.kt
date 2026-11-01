package hivens.core.io

import java.io.IOException
import java.nio.file.Path

/**
 * Resolves [relative] against [root] and refuses anything that lands outside.
 *
 * Every path the launcher writes under an instance ultimately comes from a
 * document a server sent: a file manifest, a version json, an unpack index.
 * `Path.resolve` does not normalise, so a `..` segment in any of those escapes
 * the instance silently, and the interesting targets are not inside it --
 * `~/.config/autostart`, a shell profile, the Windows Startup folder.
 *
 * A separate string-cleaning step is not a substitute. Cleaning transforms the
 * name; only comparing the resolved location against the root decides whether
 * the write is allowed, and one enforcement point is easier to keep correct
 * than a cleaning rule per caller.
 *
 * [label] names the entry as the server wrote it, so the failure says which
 * manifest line was refused rather than only where it pointed.
 *
 * **Threat model**: this defends against a document-driven traversal, from a
 * hostile or compromised upstream or an attacker in the middle. It does NOT
 * defend against a pre-existing symlink inside [root] pointing out of it --
 * the comparison is lexical, so `<root>/config -> /opt/shared` followed by an
 * entry `config/foo.cfg` writes to `/opt/shared/foo.cfg` and still passes.
 * Symlinks under an instance are the user's own. If that ever stops being
 * true (multi-tenant installs, sandboxed sync), resolve each parent with
 * `toRealPath` before comparing.
 */
@Throws(IOException::class)
fun resolveWithinRoot(root: Path, relative: String, label: String = relative): Path {
    val rootNormalized = root.normalize()
    val resolved = rootNormalized.resolve(relative).normalize()
    if (!resolved.startsWith(rootNormalized)) {
        throw IOException(
            "entry '$label' resolves outside $rootNormalized (to $resolved); refusing to touch it"
        )
    }
    return resolved
}
