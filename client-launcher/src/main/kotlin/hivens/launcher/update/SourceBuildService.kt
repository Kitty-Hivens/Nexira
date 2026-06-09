package hivens.launcher.update

import hivens.core.api.interfaces.IUpdateApplicator
import hivens.core.data.ReleaseChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

/**
 * Builds the launcher from the repository and applies the result -- the engine
 * behind the Dev/Git channels. Linux + AppImage only (the AppImage build is a
 * Linux pipeline; the produced `.AppImage` replaces the running one via the
 * normal [IUpdateApplicator]).
 *
 * `git` builds the stable branch; `dev` additionally tracks the bleeding `dev`
 * branch. Needs a real local toolchain -- git, a JDK (gradle + jlink), and
 * appimagetool -- so it is opt-in and dev-tools-gated; [detectToolchain] reports
 * what is missing and the manager surfaces that instead of pretending to build.
 *
 * Process spawns are kept thin; the decision logic ([branchFor], [gradleArgs],
 * [Toolchain.ready]) is pure and unit-tested without touching git or gradle.
 */
class SourceBuildService(
    dataDirectory: Path,
    private val applicator: IUpdateApplicator,
    // Injectable so toolchain detection is testable without a real PATH.
    private val onPath: (String) -> Boolean = ::defaultOnPath,
) {
    private val logger = LoggerFactory.getLogger(SourceBuildService::class.java)
    private val osName = System.getProperty("os.name", "").lowercase()
    private val workspace: File = dataDirectory.resolve("source").toFile()

    /** Which external tools are present. [ready] gates a build attempt. */
    data class Toolchain(val git: Boolean, val jdk: Boolean, val appImageTool: Boolean) {
        val ready: Boolean get() = git && jdk && appImageTool
        val missing: List<String> get() = buildList {
            if (!git) add("git")
            if (!jdk) add("JDK (javac)")
            if (!appImageTool) add("appimagetool")
        }
    }

    sealed interface Progress {
        /** A coarse phase change (fetch / build / package / apply). */
        data class Phase(val message: String) : Progress
        /** A raw line from a spawned process. */
        data class Line(val text: String) : Progress
    }

    /** Building from source + replacing the AppImage is Linux-AppImage only. */
    fun isSupported(): Boolean =
        osName.contains("linux") && !System.getenv("APPIMAGE").isNullOrBlank()

    fun detectToolchain(): Toolchain = Toolchain(
        git = onPath("git"),
        jdk = onPath("javac") || System.getenv("JAVA_HOME")?.let { File(it, "bin/javac").canExecute() } == true,
        appImageTool = onPath("appimagetool"),
    )

    /** `dev` tracks the dev branch; everything else (git) builds stable. */
    internal fun branchFor(channel: ReleaseChannel): String =
        if (channel == ReleaseChannel.Dev) "dev" else "stable"

    /** Gradle build args (sans the gradlew path) for a source build at [version]. */
    internal fun gradleArgs(version: String): List<String> = listOf(
        ":client-ui:packageReleaseUberJarForCurrentOS",
        ":client-ui:emitAppImageProfile",
        "-PappVersion=$version",
        "--no-daemon",
    )

    /**
     * Clones/updates the repo for [channel], builds the AppImage, and schedules
     * it for install. Emits coarse phases + raw process lines via [onProgress].
     * The caller exits the process after success so the applicator's shutdown
     * hook swaps the AppImage and relaunches (same as a downloaded update).
     */
    suspend fun buildAndApply(
        channel: ReleaseChannel,
        onProgress: (Progress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(isSupported()) { "Building from source is only supported on Linux (AppImage)." }
            val tc = detectToolchain()
            require(tc.ready) { "Missing developer tools: ${tc.missing.joinToString(", ")}." }

            val branch = branchFor(channel)
            onProgress(Progress.Phase("Fetching sources ($branch)"))
            syncRepo(branch, onProgress)

            val version = describeVersion()
            onProgress(Progress.Phase("Building $version -- this takes a few minutes"))
            run(listOf(gradlewPath()) + gradleArgs(version), onProgress)

            val jar = findReleaseJar()
                ?: error("Build produced no release jar under client-ui/build/compose/jars")

            onProgress(Progress.Phase("Packaging AppImage"))
            val output = File(workspace, "Nexira-$version-x86_64.AppImage")
            // build-appimage.sh (and jlink) refuse to overwrite an existing
            // AppDir / output, so a fresh build must clear the previous run's
            // scratch -- otherwise the second source build aborts.
            File(workspace, "AppDir").deleteRecursively()
            output.delete()
            run(
                listOf(File(workspace, "scripts/build-appimage.sh").absolutePath, version, jar.absolutePath),
                onProgress,
                extraEnv = mapOf("OUTPUT" to output.absolutePath),
            )
            require(output.isFile) { "AppImage was not produced at ${output.absolutePath}" }

            onProgress(Progress.Phase("Applying"))
            applicator.scheduleUpdate(output.toPath())
            logger.info("Source build for {} scheduled: {}", channel, output)
        }.onFailure { logger.warn("Source build failed", it) }
    }

    // ── Process orchestration (thin) ───────────────────────────────────────────

    private fun syncRepo(branch: String, onProgress: (Progress) -> Unit) {
        if (File(workspace, ".git").isDirectory) {
            run(listOf("git", "-C", workspace.absolutePath, "fetch", "--all", "--prune"), onProgress)
        } else {
            workspace.parentFile?.mkdirs()
            run(listOf("git", "clone", REPO_URL, workspace.absolutePath), onProgress)
        }
        run(listOf("git", "-C", workspace.absolutePath, "checkout", branch), onProgress)
        run(listOf("git", "-C", workspace.absolutePath, "reset", "--hard", "origin/$branch"), onProgress)
    }

    private fun gradlewPath(): String = File(workspace, "gradlew").also {
        runCatching { it.setExecutable(true) }
    }.absolutePath

    private fun describeVersion(): String = runCatching {
        val proc = ProcessBuilder("git", "-C", workspace.absolutePath, "describe", "--tags", "--always", "--dirty")
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim().removePrefix("v")
        proc.waitFor()
        if (out.contains('.')) out else "0.0.0"
    }.getOrDefault("0.0.0")

    private fun findReleaseJar(): File? =
        File(workspace, "client-ui/build/compose/jars")
            .listFiles { f -> f.name.endsWith(".jar") && f.name.contains("release", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }

    private fun run(command: List<String>, onProgress: (Progress) -> Unit, extraEnv: Map<String, String> = emptyMap()) {
        onProgress(Progress.Line("\$ ${command.joinToString(" ")}"))
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        if (workspace.isDirectory) pb.directory(workspace)
        pb.environment().putAll(extraEnv)
        val proc = pb.start()
        proc.inputStream.bufferedReader().useLines { lines -> lines.forEach { onProgress(Progress.Line(it)) } }
        val code = proc.waitFor()
        require(code == 0) { "Command failed (exit $code): ${command.joinToString(" ")}" }
    }

    companion object {
        private const val REPO_URL = "https://github.com/Kitty-Hivens/Nexira.git"

        private fun defaultOnPath(exe: String): Boolean {
            val path = System.getenv("PATH") ?: return false
            return path.split(File.pathSeparator).any { dir ->
                val f = File(dir, exe)
                f.isFile && f.canExecute()
            }
        }
    }
}
