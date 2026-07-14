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
 * Runs the app under GraalVM's tracing agent (`-agentlib:native-image-agent`)
 * on a normal GraalVM JVM, harvesting reflection / resource / serviceloader /
 * FFM (Panama) usage into [metadataOutputDir] in `config-merge-dir` mode (so
 * successive runs accumulate). `nativeImageMetadataCopy` then promotes it into
 * the committed `META-INF/native-image` resources.
 *
 * Must run on the GraalVM JVM (its `bin/java`), since the agent library ships
 * with GraalVM -- not the daemon's Liberica JDK. Always runs (it executes the
 * app); there is no meaningful UP-TO-DATE state for a diagnostic harvest.
 */
@DisableCachingByDefault(because = "diagnostic harvest run, not a build artifact")
abstract class NativeImageAgentRunTask : DefaultTask() {

    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:Input
    abstract val mainClass: Property<String>

    @get:Input
    abstract val appArgs: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val graalvmHome: Property<String>

    @get:OutputDirectory
    abstract val metadataOutputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val meta = metadataOutputDir.get().asFile
        meta.mkdirs()

        val home = resolveGraalvmHome(graalvmHome.orNull)
        val cp = classpath.files.joinToString(File.pathSeparator) { it.absolutePath }

        val args = buildList {
            add(home.javaBin())
            add("-agentlib:native-image-agent=config-merge-dir=${meta.absolutePath}")
            // Panama symbol lookup (libvault keyring etc.) happens at construction
            // time; without this the harvest run itself throws on JDK 22+.
            add("--enable-native-access=ALL-UNNAMED")
            add("-Djava.awt.headless=true")
            add("-cp"); add(cp)
            add(mainClass.get())
            addAll(appArgs.get())
        }

        logger.lifecycle("native-image-agent: harvesting into ${meta.absolutePath}")
        execOperations.exec {
            commandLine(args)
            // The app may exit non-zero (e.g. a dry-run that reaches a guard);
            // the metadata is still written, so don't fail the harvest on it.
            isIgnoreExitValue = true
        }
    }
}
