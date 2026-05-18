package hivens.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
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
 * Wraps the macOS `.app` bundle from [CustomJpackageImageTask] into a DMG
 * via `jpackage --type dmg --app-image`. Replaces Compose Desktop's
 * `packageReleaseDmg` task in CI once B-3 wires this into the macOS
 * workflow leg.
 *
 * macOS-only by construction. jpackage on Linux / Windows hosts rejects
 * `--type dmg`; the task self-skips when run off macOS so that local
 * `./gradlew customDmg` on a Linux dev box logs cleanly instead of
 * failing the build. CI is always macOS-latest for this job.
 *
 * Two flag classes go through:
 *   - identity (name, version, mac-package-identifier) -- need to match
 *     what CustomJpackageImageTask already baked into the .app, otherwise
 *     Finder shows mismatched names between bundle and disk image.
 *   - icon -- `--icon` here sets the DMG-volume icon (not the .app icon,
 *     which is already inside the bundle). Optional; jpackage falls back
 *     to a generic disk image if omitted.
 *
 * Output is a directory containing the DMG file, not the DMG itself --
 * matches jpackage's `--dest` semantics. The CI step then picks the
 * single .dmg out of that directory.
 */
@CacheableTask
abstract class CustomDmgTask : DefaultTask() {

    // ── Inputs ────────────────────────────────────────────────────────────

    /**
     * Path of the `.app` bundle produced by [CustomJpackageImageTask] on
     * macOS. On other host platforms this points at a directory that
     * does not exist; the task self-skips before the @InputDirectory
     * snapshotter runs, so the non-existence is moot.
     */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appImage: DirectoryProperty

    @get:Input
    abstract val appName: Property<String>

    @get:Input
    abstract val appVersion: Property<String>

    @get:Input
    @get:Optional
    abstract val macPackageIdentifier: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val iconFile: RegularFileProperty

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
        if (!OperatingSystem.current().isMacOsX) {
            logger.lifecycle(
                "customDmg: skipping on non-macOS host -- jpackage --type dmg " +
                    "only runs on macOS. The CI macOS leg invokes this task."
            )
            return
        }

        val out = outputDir.get().asFile
        fileSystem.delete { delete(out) }
        out.mkdirs()

        val args = buildList {
            add("${javaHome.get()}/bin/jpackage")
            add("--type"); add("dmg")
            add("--app-image"); add(appImage.get().asFile.absolutePath)
            add("--name"); add(appName.get())
            add("--app-version"); add(appVersion.get())
            add("--dest"); add(out.absolutePath)
            iconFile.orNull?.let { add("--icon"); add(it.asFile.absolutePath) }
            macPackageIdentifier.orNull?.let {
                add("--mac-package-identifier"); add(it)
            }
        }

        execOperations.exec { commandLine(args) }
    }
}
