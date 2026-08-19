package hivens.core.api.interfaces

import java.nio.file.Path

/**
 * Contract for the managed-Java-runtime service.
 *
 * The launcher ships its own per-MC-version BellSoft Liberica JDKs into the
 * data directory rather than relying on whatever the user has installed
 * system-wide. This interface is the abstraction other launch-pipeline
 * components depend on, so tests can substitute a fake without spinning
 * up the actual download path.
 */
interface IJavaManager {

    /**
     * Version-keyed shortcut: derives the Java major via [detectJavaVersion] and
     * provisions the JDK for it. Suitable for the SC server path which has no
     * concept of a loader-declared Java. Pack-centric callers should use
     * [getJavaPathForMajor] instead, with the major from the resolved runtime --
     * the JDK is fundamentally major-keyed; MC version is only a heuristic input.
     */
    suspend fun getJavaPath(version: String): Path

    /**
     * Major-keyed entry point used by the pack path. Same Minecraft version on a
     * different loader can need a different Java (e.g. Cleanroom-1.12.2 -> 25 vs
     * legacy-Forge-1.12.2 -> 8), so the pack path must pass the loader-declared
     * major directly instead of guessing from the version string.
     *
     * [onProgress] receives human-readable status lines for the launch UI -- a
     * download of a missing JDK is ~200 MB and the caller may sit on a fixed
     * progress stage while it runs; the default no-op keeps the SC server path
     * silent.
     */
    suspend fun getJavaPathForMajor(javaMajor: Int, onProgress: (String) -> Unit = {}): Path

    /**
     * Fallback Java major for a Minecraft version, used only when nothing more
     * authoritative declares it (Mojang's per-version `javaVersion` absent + no
     * loader override). Also drives launch-arg choices that depend on the JVM
     * generation (e.g. `-noverify`, deprecated since 13).
     *
     * The version is read as numbers rather than matched by its leading text.
     * Minecraft left the 1.x line behind, and a table of prefixes has no branch
     * for a release numbered by year -- every one of them fell through to the
     * oldest answer in the table, so a current pack was told it needed Java 8.
     * A version above the 1.x line is newer than anything the table describes
     * and is given the newest runtime instead.
     *
     * Unreadable input keeps the old answer. It is what a pre-1.0 or otherwise
     * unnumbered build is, and guessing modern for something that cannot say
     * what it is would break the packs this fallback exists for.
     */
    fun detectJavaVersion(mcVersion: String): Int {
        val parts = mcVersion.trim().split('.').map { part -> part.takeWhile(Char::isDigit) }
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return LEGACY_JAVA
        if (major != 1) return NEWEST_JAVA
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return LEGACY_JAVA
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return when {
            minor > 20 -> 21
            // 1.20.5 is where the 1.20 line moved up, so the patch decides here
            // and nowhere else on it.
            minor == 20 && patch >= 5 -> 21
            minor >= 17 -> 17
            else -> LEGACY_JAVA
        }
    }

    companion object {
        /** What Minecraft ran on before the 1.17 rewrite, and the answer for anything older. */
        const val LEGACY_JAVA = 8

        /** The runtime for a release numbered past the 1.x line. */
        const val NEWEST_JAVA = 25
    }
}
