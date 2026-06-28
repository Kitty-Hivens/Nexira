package hivens.nativeimage

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.tasks.Copy
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Convention plugin for building the headless CLI to a GraalVM / Liberica-NIK
 * native binary. Apply via:
 *
 * ```kotlin
 * plugins { id("nexira.native-image") }
 *
 * nativeImage {
 *     imageName.set("nexira-cli")
 *     // mainClass defaults from the `application` plugin
 *     buildArgs.add("--initialize-at-run-time=...")  // appends to defaults
 * }
 * ```
 *
 * Registers three tasks under the `native-image` group:
 *   - `nativeImage`              -- build the binary
 *   - `nativeImageAgentRun`      -- harvest reachability metadata via the agent
 *   - `nativeImageMetadataCopy`  -- promote harvested metadata into resources
 *
 * Mirrors [hivens.packaging.PackagingPlugin]: typed `ExecOperations` tasks, no
 * third-party plugin on the classpath, config-cache clean. The difference is
 * tool location -- `native-image` comes from a GraalVM/NIK install, not the
 * Gradle JDK, resolved at execution time (see [resolveGraalvmHome]).
 */
class NativeImagePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("nativeImage", NativeImageExtension::class.java)

        // Defaults. native-access + headless are belt-and-suspenders for the
        // Panama keyring and the AWT-free guarantee; --no-fallback makes a
        // missing-metadata gap a hard build failure rather than a silent
        // JVM-fallback image. No --gc=G1: that is Oracle-GraalVM-only and would
        // break on Liberica NIK (CE-based, serial GC).
        ext.imageName.convention("app")
        ext.buildArgs.convention(
            listOf(
                "--no-fallback",
                "-H:+UnlockExperimentalVMOptions",
                "--enable-native-access=ALL-UNNAMED",
                "-Djava.awt.headless=true",
            )
        )
        ext.agentArgs.convention(listOf("list"))
        ext.outputDir.convention(project.layout.buildDirectory.dir("nativeImage"))
        ext.agentOutputDir.convention(project.layout.buildDirectory.dir("native/agent-output"))
        ext.committedMetadataDir.convention(
            ext.imageName.map { name ->
                project.layout.projectDirectory.dir("src/main/resources/META-INF/native-image/hivens/$name")
            }
        )

        // Default mainClass from the application plugin so consumers declare it once.
        project.plugins.withId("application") {
            val app = project.extensions.getByType(JavaApplication::class.java)
            ext.mainClass.convention(app.mainClass)
        }

        // GraalVM home from env via providers (config-cache safe); the ext value
        // wins when set. Falls back to a task-time scan when neither is present.
        val graalvmHomeProvider = ext.graalvmHome
            .orElse(project.providers.environmentVariable("GRAALVM_HOME"))
            .orElse(project.providers.environmentVariable("NATIVE_IMAGE_HOME"))

        // Optional CPU-target override: -PnativeMarch=native|x86-64-v2|... appends
        // -march=<value>. Unset = GraalVM's default baseline (x86-64-v3, needs AVX2).
        // Use 'native' for a local-only max-perf binary; a portable floor (e.g.
        // x86-64-v2) for one you distribute -- a 'native' binary SIGILLs on a CPU
        // older than the build host. `native-image -march=list` shows the options.
        val marchArgs = project.providers.gradleProperty("nativeMarch")
            .map { listOf("-march=$it") }
            .orElse(emptyList())

        // jar carries our compiled classes + resources (incl. committed
        // META-INF/native-image metadata); runtimeClasspath is the deps.
        // `from(provider)` carries the implicit task dependency on :jar.
        val jar = project.tasks.named<Jar>("jar")
        val runtimeClasspath = project.configurations.named("runtimeClasspath")

        project.tasks.register<NativeImageBuildTask>("nativeImage") {
            group = "native-image"
            description = "Builds a native binary via GraalVM / Liberica-NIK native-image."
            classpath.from(jar.flatMap { it.archiveFile })
            classpath.from(runtimeClasspath)
            mainClass.convention(ext.mainClass)
            imageName.convention(ext.imageName)
            buildArgs.convention(ext.buildArgs.zip(marchArgs) { base, march -> base + march })
            graalvmHome.convention(graalvmHomeProvider)
            outputDir.convention(ext.outputDir)
        }

        project.tasks.register<NativeImageAgentRunTask>("nativeImageAgentRun") {
            group = "native-image"
            description = "Runs the app under the native-image tracing agent to harvest reachability metadata. " +
                "Override args with -PnativeAgentArgs=\"launch <id> --dry-run\"."
            classpath.from(jar.flatMap { it.archiveFile })
            classpath.from(runtimeClasspath)
            mainClass.convention(ext.mainClass)
            appArgs.convention(
                project.providers.gradleProperty("nativeAgentArgs")
                    .map { it.split(" ").filter(String::isNotBlank) }
                    .orElse(ext.agentArgs)
            )
            graalvmHome.convention(graalvmHomeProvider)
            metadataOutputDir.convention(ext.agentOutputDir)
        }

        project.tasks.register<Copy>("nativeImageMetadataCopy") {
            group = "native-image"
            description = "Promotes agent-harvested metadata into the committed META-INF/native-image resources."
            from(ext.agentOutputDir)
            into(ext.committedMetadataDir)
        }
    }
}

/**
 * Locates a GraalVM / Liberica-NIK home containing an executable
 * `bin/native-image`, vendor-agnostically. Resolution order: [explicit] (from
 * the ext / GRAALVM_HOME / NATIVE_IMAGE_HOME) -> `/usr/lib/jvm` scan -> `PATH`.
 * Runs at execution time, so the filesystem / PATH access is config-cache safe.
 */
internal fun resolveGraalvmHome(explicit: String?): File {
    if (!explicit.isNullOrBlank()) {
        val home = File(explicit)
        require(File(home, "bin/native-image").canExecute()) {
            "graalvmHome / GRAALVM_HOME='$explicit' has no executable bin/native-image. " +
                "Point it at a GraalVM or Liberica NIK 25 install."
        }
        return home
    }

    // Scan /usr/lib/jvm. Prefer a Liberica NIK install (the project's
    // documented toolchain) over any other GraalVM when both are present;
    // otherwise take the highest-named candidate.
    val scanned = File("/usr/lib/jvm").listFiles()
        ?.filter { File(it, "bin/native-image").canExecute() }
        ?.sortedByDescending { it.name }
        .orEmpty()
    (scanned.firstOrNull { it.name.contains("nik", ignoreCase = true) || it.name.contains("liberica", ignoreCase = true) }
        ?: scanned.firstOrNull())
        ?.let { return it }

    System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
        val ni = File(dir, "native-image")
        if (ni.canExecute()) return ni.absoluteFile.parentFile.parentFile
    }

    error(
        "native-image not found. Install GraalVM or Liberica NIK 25 and set GRAALVM_HOME. " +
            "With SDKMAN:  sdk install nik  &&  export GRAALVM_HOME=\"\$HOME/.sdkman/candidates/nik/current\". " +
            "Or set the nativeImage { graalvmHome } property. See docs/native-image.md."
    )
}

internal fun File.nativeImageBin(): String = File(this, "bin/native-image").absolutePath

internal fun File.javaBin(): String = File(this, "bin/java").absolutePath
