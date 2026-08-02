package hivens.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
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
 * rebuilds; running twice with no changes is a no-op. The JDK path is
 * declared as `@Input` (string) too -- swapping the toolchain JDK
 * invalidates the cache, which is the desired semantic.
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
}
