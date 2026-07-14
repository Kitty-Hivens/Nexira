package hivens.nativeimage

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.inject.Inject

/**
 * Invokes `native-image` to produce a standalone binary from the consuming
 * module's runtime classpath + jar.
 *
 * Not cacheable: the output is a large, host-specific (glibc/arch) executable
 * and the build is the artifact, not a relocatable intermediate. UP-TO-DATE is
 * still tracked via [classpath] (deps + jar, which carries the committed
 * reachability metadata), [buildArgs], [mainClass] and the output dir, so a
 * no-change rerun is a no-op and a metadata/dep change rebuilds.
 *
 * The toolchain is resolved at execution time ([resolveGraalvmHome]) rather
 * than via the Gradle JDK, because `native-image` lives in a GraalVM / Liberica
 * NIK distribution, not in the daemon's JDK.
 */
@DisableCachingByDefault(because = "native-image output is host-specific and large; the binary is the artifact")
abstract class NativeImageBuildTask : DefaultTask() {

    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:Input
    abstract val mainClass: Property<String>

    @get:Input
    abstract val imageName: Property<String>

    @get:Input
    abstract val buildArgs: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val graalvmHome: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.mkdirs()

        val home = resolveGraalvmHome(graalvmHome.orNull)
        val cp = classpath.files.joinToString(File.pathSeparator) { it.absolutePath }

        val args = buildList {
            add(home.nativeImageBin())
            addAll(buildArgs.get())
            add("-cp"); add(cp)
            add("-o"); add(out.resolve(imageName.get()).absolutePath)
            add(mainClass.get())
        }

        logger.lifecycle("native-image: ${home.nativeImageBin()} -> ${out.resolve(imageName.get())}")
        execOperations.exec { commandLine(args) }
    }
}
