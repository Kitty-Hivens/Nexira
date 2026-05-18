package hivens.packaging

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/**
 * Convention plugin for Aura's packaging tasks. Apply via:
 *
 * ```kotlin
 * plugins { id("aura.packaging") }
 *
 * packaging {
 *     appName.set("AuraLauncher")
 *     mainClass.set("hivens.ui.MainKt")
 *     modules.set(listOf("java.base", "java.desktop", ...))
 *     jlink {
 *         compress.set("zip-9")
 *         vmKind.set("server")
 *         includeLocales.set("en,ru,de")
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
 * who wants the unaltered runtime can omit them. The `aura.packaging`
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

            javaHome.convention(resolvedJavaHome)
            outputDir.convention(
                project.layout.buildDirectory.dir("customRuntime")
            )
        }
    }
}
