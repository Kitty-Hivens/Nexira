package hivens.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Produces a custom JDK runtime image via `jlink`, configured from
 * [PackagingExtension]'s [JlinkOptionsExtension] values.
 *
 * Replaces the off-the-shelf badass-runtime-plugin path. Three concrete
 * blockers made that plugin a poor fit for Nexira: hard plugin-apply conflict
 * with Compose Multiplatform's `run` task, `Task.project` usage at
 * execution time that trips the project-wide strict configuration-cache
 * policy, and a competing source of truth for jlink flags (DSL vs the
 * shell-based AppImage path). A typed Gradle task against jlink directly
 * sidesteps all three.
 *
 * UP-TO-DATE behaviour: every flag is declared as `@Input`; the output
 * runtime directory is `@OutputDirectory`. Changing a flag invalidates and
 * rebuilds; running twice with no changes is a no-op. The JDK is tracked by
 * its path and by the content of its `release` file, so both a different JDK
 * and an upgrade of the same one in place invalidate the image.
 *
 * `@CacheableTask` makes the output relocatable through the Gradle build
 * cache (local + remote); jlink output is deterministic for fixed inputs
 * so this is safe.
 */
@CacheableTask
abstract class CustomRuntimeTask : DefaultTask() {

    // ── Inputs ────────────────────────────────────────────────────────────

    @get:Input
    abstract val modules: ListProperty<String>

    @get:Input
    abstract val stripDebug: Property<Boolean>

    @get:Input
    abstract val noHeaderFiles: Property<Boolean>

    @get:Input
    abstract val noManPages: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val compress: Property<String>

    @get:Input
    @get:Optional
    abstract val vmKind: Property<String>

    @get:Input
    @get:Optional
    abstract val includeLocales: Property<String>

    /** Base CDS archive (`lib/server/classes.jsa`). Without it in the image, app-class
     *  sharing (`-XX:ArchiveClassesAtExit`, `-XX:+AutoCreateSharedArchive`) is
     *  rejected by the JVM, so class loading cannot be cut off the cold start. */
    @get:Input
    abstract val generateCdsArchive: Property<Boolean>

    /**
     * Module-system flags the archive is dumped under -- the [ModuleSystemArgs]
     * subset of the launcher's jvmArgs. The dump has to see the same set the
     * launcher passes, or the JVM discards the archived module graph and logs a
     * `Mismatched values for property jdk.module.*` error per flag on every run.
     */
    @get:Input
    abstract val cdsDumpArgs: ListProperty<String>

    /**
     * Absolute path of the JDK whose jlink + jmods we invoke. Treated as
     * an `@Input` string so swapping JDK installation paths invalidates the
     * cached runtime image (which is the right behaviour -- different JDKs
     * produce different runtimes even with identical flags).
     */
    @get:Input
    abstract val javaHome: Property<String>

    /**
     * The JDK's own `release` file, which names its exact build. The path alone
     * left the task up to date across an in-place upgrade of the same JDK, and the
     * release then shipped the runtime of the build that had been replaced. Read
     * by content, so a new build at the same path rebuilds the image.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jdkRelease: RegularFileProperty

    // ── Outputs ───────────────────────────────────────────────────────────

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    // ── Services ──────────────────────────────────────────────────────────

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    // ── Action ────────────────────────────────────────────────────────────

    @TaskAction
    fun runJlink() {
        val out = outputDir.get().asFile
        val jdkHome = javaHome.get()

        // jlink refuses to write into an existing directory, even if empty.
        // The OS-level rmdir before invocation matches what
        // scripts/build-appimage.sh does. FileSystemOperations.delete is
        // configuration-cache safe (vs raw File.deleteRecursively at
        // execution time hitting project state).
        fileSystem.delete { delete(out) }

        val args = buildList {
            add("$jdkHome/bin/jlink")
            add("--module-path"); add("$jdkHome/jmods")
            add("--add-modules"); add(modules.get().joinToString(","))
            if (stripDebug.get()) add("--strip-debug")
            if (noHeaderFiles.get()) add("--no-header-files")
            if (noManPages.get()) add("--no-man-pages")
            compress.orNull?.let { add("--compress=$it") }
            vmKind.orNull?.let { add("--vm=$it") }
            includeLocales.orNull?.let { add("--include-locales=$it") }
            add("--output"); add(out.absolutePath)
        }

        execOperations.exec { commandLine(args) }

        restoreSignatures(out.resolve("bin"), File(jdkHome, "bin"))

        // Base archive via the image's own `java -Xshare:dump`, not jlink's
        // --generate-cds-archive: jlink dumps with a bare flag set, and an archive
        // whose recorded jdk.module.* properties differ from the launcher's is
        // rejected for module-graph purposes at every launch. Passing cdsDumpArgs
        // here is what keeps the two ends equal.
        //
        // Bonus over the jlink plugin: -Xshare:dump writes only the compressed-oops
        // archive. jlink also emitted classes_nocoops.jsa for the >32 GB heap mode
        // the launcher never reaches -- ~14 MB of image that had to be deleted again.
        if (generateCdsArchive.get()) {
            execOperations.exec {
                commandLine(
                    buildList {
                        add(out.resolve("bin/java").absolutePath)
                        add("-Xshare:dump")
                        addAll(cdsDumpArgs.get())
                    }
                )
            }
        }
    }

    /**
     * Puts the JDK vendor's signatures back on the image's Windows binaries.
     *
     * jlink takes the native files from the jmods, and the vendor signs only the
     * finished JDK, so every dll and exe of the image came out unsigned:
     * `jvm.dll`, `java.exe`, `awt.dll` and the rest. Windows' Smart App Control
     * blocks an unsigned binary it has no reputation for, whoever loads it. The
     * same files sit signed in the JDK's own `bin`, so each one that is the same
     * image apart from its signature (see [PeSignatures]) is replaced by that
     * copy. One that differs keeps what jlink wrote, and is named in the log.
     * Measured on Liberica 27.0.1 for Windows: all 124 natives in its jmods match
     * their signed copies. Off Windows there is nothing to match, and nothing is
     * touched.
     */
    private fun restoreSignatures(imageBin: File, jdkBin: File) {
        if (!imageBin.isDirectory || !jdkBin.isDirectory) return
        var restored = 0
        val unmatched = ArrayList<String>()
        imageBin.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in PE_EXTENSIONS }
            .forEach { file ->
                val built = file.readBytes()
                if (PeSignatures.isSigned(built)) return@forEach
                val relative = file.relativeTo(imageBin).path
                val source = jdkBin.resolve(relative)
                val signed = if (source.isFile) source.readBytes() else null
                if (signed == null || !PeSignatures.isSigned(signed) || !PeSignatures.sameImage(built, signed)) {
                    unmatched += relative
                    return@forEach
                }
                source.copyTo(file, overwrite = true)
                restored++
            }
        if (restored == 0 && unmatched.isEmpty()) return
        logger.lifecycle("customRuntime: restored the JDK's signature on $restored binaries")
        if (unmatched.isNotEmpty()) {
            logger.warn("customRuntime: ${unmatched.size} binaries stay unsigned, no signed copy of the same image in $jdkBin: ${unmatched.joinToString()}")
        }
    }

    private companion object {
        val PE_EXTENSIONS = setOf("dll", "exe")
    }
}
