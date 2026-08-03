package hivens.update

import java.nio.file.Path

/**
 * The managed, writable install directory the launcher runs from after the first-run
 * bootstrap. Mirrors the app-image contents; the shipped AppImage / installer only
 * populates it once, then every self-update patches files here. Its [manifestFile]
 * records what is installed and is the diff baseline for the next update.
 */
class InstallLayout(val root: Path) {
    /** jlink Liberica JRE -- changes only on a runtime bump, never re-downloaded otherwise. */
    val runtimeDir: Path = root.resolve("runtime")
    /** The app uber jar; the file a self-update almost always patches. */
    val appJar: Path = root.resolve("lib").resolve("nexira.jar")
    /** Host-only skinema/FFmpeg + JNA dispatchers. */
    val nativesDir: Path = root.resolve("natives")
    /** profiler-agent.jar, authlib-agent.jar. */
    val agentsDir: Path = root.resolve("agents")
    /** Exec stub: `java -jar lib/nexira.jar` with the mirrored JVM flags. */
    val launchStub: Path = root.resolve("launch")
    /** Plain semver marker of the installed version. */
    val versionFile: Path = root.resolve("version")
    /** Leyden AOT cache for a faster cold start; loaded via -XX:AOTCache. Built against
     *  the app jar's exact bytes, so a jar-changing update invalidates it. */
    val aotCache: Path = root.resolve("app.aot")
    /** FileManifest of what is installed (the update baseline). */
    val manifestFile: Path = root.resolve("manifest.json")
    /** Where an in-flight update downloads + patches before the atomic swap. */
    val stagingDir: Path = root.resolve("staging")

    /** Derived / update scaffolding that is NOT shipped content and must never enter
     *  a content manifest: the recorded manifest itself, the version marker, the AOT
     *  cache (client-generated), and the staging area. */
    val bookkeeping: Set<Path> = setOf(stagingDir, manifestFile, versionFile, aotCache)

    companion object {
        /** The managed layout lives under the launcher data root, not inside the
         *  read-only shipped bundle (a macOS /Applications .app is not user-writable). */
        fun forDataDir(dataDir: Path): InstallLayout = InstallLayout(dataDir.resolve("app"))
    }
}
