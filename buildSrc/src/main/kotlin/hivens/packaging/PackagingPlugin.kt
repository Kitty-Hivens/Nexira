package hivens.packaging

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.internal.os.OperatingSystem
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

/**
 * Convention plugin for Nexira's packaging tasks. Apply via:
 *
 * ```kotlin
 * plugins { id("nexira.packaging") }
 *
 * packaging {
 *     appName.set("Nexira")
 *     mainClass.set("hivens.ui.MainKt")
 *     modules.set(listOf("java.base", "java.desktop", ...))
 *     jlink {
 *         vmKind.set("server")
 *         includeLocales.set("en,ru,de")
 *         // compress left unset on purpose -- see PackagingExtension.
 *     }
 * }
 * ```
 *
 * Currently registers the [CustomRuntimeTask] under the task name
 * `customRuntime`. Subsequent phases (B-2 / B-3) extend this plugin with
 * the AppImage shell-profile emitter and the jpackage image task.
 *
 * Defaults: strip-debug + no-header-files + no-man-pages are set to true
 * out of the box (small, safe, only-want-them-on for distribution builds);
 * compress / vmKind / includeLocales stay unset by default so a consumer
 * who wants the unaltered runtime can omit them. The `nexira.packaging`
 * consumer always sets all three explicitly in client-ui/build.gradle.kts.
 */
class PackagingPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("packaging", PackagingExtension::class.java)

        // Distribution-friendly defaults. Override per-consumer if a
        // particular build needs different shape (none today).
        ext.jlink.stripDebug.convention(true)
        ext.jlink.noHeaderFiles.convention(true)
        ext.jlink.noManPages.convention(true)
        // On by default: without the base CDS archive the JVM refuses app-class
        // sharing entirely, which is the only cheap lever left on cold start.
        ext.jlink.generateCdsArchive.convention(true)
        // compress / vmKind / includeLocales: deliberately no convention
        // value -- omission of the flag is meaningful to jlink, and
        // forcing a default would hide that signal.

        // The JDK running Gradle is the one we hand to jlink. Matches
        // scripts/build-appimage.sh and Compose Desktop's default. If we
        // ever want a different JDK for packaging than for compilation,
        // wire JavaToolchainService here -- not needed yet.
        val resolvedJavaHome = System.getProperty("java.home")
            ?: error("System property java.home is not set; cannot locate jlink.")

        project.tasks.register<CustomRuntimeTask>("customRuntime") {
            group = "packaging"
            description = "Builds a custom JDK runtime image via jlink for distribution."

            modules.convention(ext.modules)
            stripDebug.convention(ext.jlink.stripDebug)
            noHeaderFiles.convention(ext.jlink.noHeaderFiles)
            noManPages.convention(ext.jlink.noManPages)
            compress.convention(ext.jlink.compress)
            vmKind.convention(ext.jlink.vmKind)
            includeLocales.convention(ext.jlink.includeLocales)
            generateCdsArchive.convention(ext.jlink.generateCdsArchive)

            javaHome.convention(resolvedJavaHome)
            outputDir.convention(
                project.layout.buildDirectory.dir("customRuntime")
            )
        }

        project.tasks.register<EmitAppImageProfileTask>("emitAppImageProfile") {
            group = "packaging"
            description =
                "Writes a shell-sourceable file with the jlink modules and flags, " +
                "so scripts/build-appimage.sh stays in lockstep with the gradle-side " +
                "PackagingExtension."

            modules.convention(ext.modules)
            stripDebug.convention(ext.jlink.stripDebug)
            noHeaderFiles.convention(ext.jlink.noHeaderFiles)
            noManPages.convention(ext.jlink.noManPages)
            compress.convention(ext.jlink.compress)
            vmKind.convention(ext.jlink.vmKind)
            includeLocales.convention(ext.jlink.includeLocales)
            generateCdsArchive.convention(ext.jlink.generateCdsArchive)

            outputFile.convention(
                project.layout.buildDirectory.file("generated/packaging/packaging-profile.sh")
            )
        }

        // Pick the platform-appropriate icon at config time. Linux falls
        // through to "no icon" -- jpackage is fine without one for
        // app-image type, and the Linux distributable is AppImage which
        // handles icons separately via .desktop entry.
        val osIcon = when {
            OperatingSystem.current().isWindows -> ext.windowsIcon
            OperatingSystem.current().isMacOsX -> ext.macosIcon
            else -> null
        }

        val customRuntime = project.tasks.named<CustomRuntimeTask>("customRuntime")

        // Register the task at apply time so other parts of the build can
        // reference it. The mainJar convention has to wait for Compose
        // Desktop's packageReleaseUberJarForCurrentOS to be registered --
        // Compose-MP defers that registration into its own afterEvaluate
        // block once the `kotlin { jvm("desktop") }` target is processed,
        // so we wire below in our own afterEvaluate.
        val customJpackageImage = project.tasks.register<CustomJpackageImageTask>("customJpackageImage") {
            group = "packaging"
            description = "Builds a jpackage app-image from the custom runtime and Compose Desktop's uber jar."

            appName.convention(ext.appName)
            mainClass.convention(ext.mainClass)
            appVersion.convention(ext.appVersion)
            jvmArgs.convention(ext.jvmArgs)
            macPackageIdentifier.convention(ext.macosPackageIdentifier)

            runtimeImage.convention(customRuntime.flatMap { it.outputDir })

            osIcon?.let { iconFile.convention(it) }

            javaHome.convention(resolvedJavaHome)
            outputDir.convention(project.layout.buildDirectory.dir("customJpackageImage"))
        }

        project.afterEvaluate {
            // packageReleaseUberJarForCurrentOS extends Jar, so flatMap
            // through its archiveFile gives us a Provider<RegularFile>
            // that doubles as an implicit task-dependency on the producer.
            // No explicit dependsOn needed.
            val composeReleaseUberJar = project.tasks.named<Jar>("packageReleaseUberJarForCurrentOS")
            customJpackageImage.configure {
                mainJar.convention(composeReleaseUberJar.flatMap { it.archiveFile })
            }
        }

        // DMG wrap -- macOS-only, consumes the .app bundle from
        // customJpackageImage. Registered unconditionally so the task
        // surface is consistent across hosts; the task body self-skips
        // when not on macOS.
        project.tasks.register<CustomDmgTask>("customDmg") {
            group = "packaging"
            description = "Wraps the macOS .app bundle from customJpackageImage into a DMG."

            appName.convention(ext.appName)
            appVersion.convention(ext.appVersion)
            macPackageIdentifier.convention(ext.macosPackageIdentifier)
            // macOS DMG-volume icon shares the .icns we set on the bundle.
            iconFile.convention(ext.macosIcon)

            // jpackage --type app-image on macOS lands the bundle at
            // <outputDir>/<appName>.app. The provider chain resolves
            // lazily and carries the implicit dependency on
            // customJpackageImage.
            appImage.convention(
                ext.appName.flatMap { name ->
                    customJpackageImage.flatMap { it.outputDir.dir("$name.app") }
                }
            )

            javaHome.convention(resolvedJavaHome)
            outputDir.convention(project.layout.buildDirectory.dir("customDmg"))
        }
    }
}
