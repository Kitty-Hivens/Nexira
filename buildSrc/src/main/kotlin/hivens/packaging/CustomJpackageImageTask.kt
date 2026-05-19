package hivens.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.os.OperatingSystem
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Produces a `--type app-image` directory via `jpackage`, consuming the
 * pre-built runtime image from [CustomRuntimeTask] and the uber jar from
 * Compose Desktop's `packageReleaseUberJarForCurrentOS`. Output is the
 * platform-native app image (e.g. `Nexira/` with `bin/Nexira`
 * on Linux / Windows, `Nexira.app/` bundle on macOS).
 *
 * For Nexira's release pipeline this is the input to two further wrapping
 * steps that B-3 wires up:
 *   - Windows: Inno Setup (`setup.iss`) packs the app-image dir into the
 *     installer EXE.
 *   - macOS: `hdiutil` (or jpackage's own `--type dmg` in a second call)
 *     wraps the .app bundle into a DMG.
 *
 * Linux is intentionally NOT a target here -- the AppImage path
 * (`scripts/build-appimage.sh`) is the canonical Linux distributable.
 * The task can still run on Linux successfully for local validation
 * (produces a generic Linux app-image directory).
 *
 * Per-platform handling stays inside the task body via
 * [OperatingSystem.current] rather than splitting into multiple task
 * subclasses -- the differences are a handful of flags, splitting would
 * be premature abstraction.
 *
 * Staging directory: jpackage's `--input` is a directory that gets
 * wholesale-copied into the produced app image's `app/` subdir. We
 * point it at a single-file staging dir containing only the uber jar
 * so unrelated jars in `build/compose/jars/` (per-platform variants
 * that may coexist) do not pollute the output.
 *
 * UP-TO-DATE behaviour: every input is typed. Mutating any flag, the
 * runtime image, or the uber jar invalidates the cached app image.
 * `@CacheableTask` lets the local Gradle build cache restore prior
 * outputs across flag-flip cycles.
 */
@CacheableTask
abstract class CustomJpackageImageTask : DefaultTask() {

    // ── Inputs from PackagingExtension ────────────────────────────────────

    @get:Input
    abstract val appName: Property<String>

    @get:Input
    abstract val mainClass: Property<String>

    @get:Input
    abstract val appVersion: Property<String>

    @get:Input
    abstract val jvmArgs: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val macPackageIdentifier: Property<String>

    // ── Inputs from upstream tasks / consumer ─────────────────────────────

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeImage: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val mainJar: RegularFileProperty

    /**
     * Optional platform-specific icon. The plugin's task wiring picks
     * `windowsIcon` on Windows, `macosIcon` on macOS, and leaves this
     * unset on Linux. jpackage simply omits the flag when not present.
     */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val iconFile: RegularFileProperty

    /** Absolute path of the JDK whose `jpackage` we invoke. */
    @get:Input
    abstract val javaHome: Property<String>

    // ── Output ────────────────────────────────────────────────────────────

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    // ── Services ──────────────────────────────────────────────────────────

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    // ── Action ────────────────────────────────────────────────────────────

    @TaskAction
    fun runJpackage() {
        val out = outputDir.get().asFile
        val jdkHome = javaHome.get()
        val jar = mainJar.get().asFile

        // jpackage refuses to write into an existing non-empty `--dest`,
        // and even a stale staging dir from a previous run would mix
        // files in. Wipe both before invocation.
        fileSystem.delete { delete(out) }
        out.mkdirs()

        val staging = out.parentFile.resolve("${out.name}-staging")
        fileSystem.delete { delete(staging) }
        staging.mkdirs()
        fileSystem.copy {
            from(jar)
            into(staging)
        }

        val os = OperatingSystem.current()
        val args = buildList {
            add("$jdkHome/bin/jpackage")
            add("--type"); add("app-image")
            add("--name"); add(appName.get())
            add("--app-version"); add(appVersion.get())
            add("--input"); add(staging.absolutePath)
            add("--main-jar"); add(jar.name)
            add("--main-class"); add(mainClass.get())
            add("--runtime-image"); add(runtimeImage.get().asFile.absolutePath)
            add("--dest"); add(out.absolutePath)
            iconFile.orNull?.let {
                add("--icon"); add(it.asFile.absolutePath)
            }
            // jpackage takes --java-options multiple times, one per flag.
            jvmArgs.get().forEach {
                add("--java-options"); add(it)
            }
            if (os.isMacOsX) {
                macPackageIdentifier.orNull?.let {
                    add("--mac-package-identifier"); add(it)
                }
            }
        }

        execOperations.exec { commandLine(args) }
    }
}
