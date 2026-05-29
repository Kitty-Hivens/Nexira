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
     * Fallback Java major for a Minecraft version (8 / 17 / 21), used only when
     * nothing more authoritative declares it (Mojang's per-version `javaVersion`
     * absent + no loader override). Also drives launch-arg choices that depend
     * on the JVM generation (e.g. `-noverify`, deprecated since 13).
     */
    fun detectJavaVersion(mcVersion: String): Int = when {
        mcVersion.startsWith("1.21") || mcVersion.startsWith("1.20.5") || mcVersion.startsWith("1.20.6") -> 21
        mcVersion.startsWith("1.17") || mcVersion.startsWith("1.18") ||
            mcVersion.startsWith("1.19") || mcVersion.startsWith("1.20") -> 17
        else -> 8
    }
}
