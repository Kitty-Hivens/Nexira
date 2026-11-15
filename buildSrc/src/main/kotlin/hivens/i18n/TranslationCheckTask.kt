package hivens.i18n

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.net.URLClassLoader

/**
 * Warns about a namespace's shipped translations: keys English has that another language
 * lacks, keys the code looks up that English does not have, and messages that do not parse.
 *
 * Never fails: a missing translation shows its key at runtime, which is a thing to fix and
 * not a reason to stop a build. The check itself is the language module's own
 * (`hivens.i18n.TableCheck`), loaded from [checker], so the build reads messages with the
 * very parser the launcher does. It runs every time, since a warning that only shows on the
 * build after an edit is one nobody sees twice.
 */
@DisableCachingByDefault(because = "Prints warnings and produces nothing to cache")
abstract class TranslationCheckTask : DefaultTask() {

    /** The class path the language module's check runs from. */
    @get:Classpath
    abstract val checker: ConfigurableFileCollection

    /** The folder of the namespace's tables, one `<tag>.properties` per language. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val tables: DirectoryProperty

    /** The language every other one is checked against. */
    @get:Input
    abstract val english: Property<String>

    /** The Kotlin sources whose literal lookups are checked against English. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    init {
        doNotTrackState("A warning has to show on every build, not only the one after an edit")
    }

    @TaskAction
    fun check() {
        val urls = checker.files.map { it.toURI().toURL() }.toTypedArray()
        val warnings = URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            val type = loader.loadClass("hivens.i18n.TableCheck")
            val method = type.getMethod("check", File::class.java, String::class.java, List::class.java)
            @Suppress("UNCHECKED_CAST")
            method.invoke(null, tables.get().asFile, english.get(), sources.files.sortedBy { it.path }.toList()) as List<String>
        }
        warnings.forEach { logger.warn("w: translations: {}", it) }
        if (warnings.isEmpty()) logger.lifecycle("Translations: no warnings")
    }
}
