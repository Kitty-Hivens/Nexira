package hivens.look

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

/**
 * The build of a module under `look/`: the manager, an axis contract, a provider or a pack.
 *
 * ```kotlin
 * plugins { id("nexira.look") }
 *
 * look { api() }
 *
 * kotlin.sourceSets.named("jvmSharedMain") {
 *     dependencies { api(project(":widget-api")) }
 * }
 * ```
 *
 * Every look module gets what every multiplatform module of the launcher has: a desktop and an
 * Android target over one shared JVM source set, the Compose compiler, the JDK 27 toolchain, and
 * JUnit for its tests. Its Android namespace comes from its name, `:look:colour-nx` being
 * `hivens.look.colour.nx`. A module other modules compile against says so with [LookModuleExtension.api],
 * which switches on its ABI dump and hands its version to its tests. The version is the Gradle property
 * the module's name gives, `nexira.lookManagerApi` for `:look:manager-api`, kept in `gradle.properties`
 * beside the other module APIs' so a jar's build stamps its manifest from the same line.
 *
 * A module's suite runs through `:look:allTests`, which the test workflow names once, so applying
 * this to a module under `look/` is what puts its tests in CI.
 */
class LookModulePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        if (!project.path.startsWith(":look:")) {
            throw GradleException("${project.path} is not under :look, and only a look module is built by nexira.look")
        }
        project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        project.pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        project.pluginManager.apply("com.android.kotlin.multiplatform.library")

        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        val names = LookModuleNames(project.name)
        project.extensions.create("look", LookModuleExtension::class.java, project, kotlin, names)

        // KMP modules do not apply the `java` plugin, so the root's toolchain pin skips them.
        kotlin.jvmToolchain(27)
        kotlin.jvm()
        (kotlin as ExtensionAware).extensions.configure(KotlinMultiplatformAndroidLibraryTarget::class.java) {
            namespace = names.namespace
            compileSdk = project.providers.gradleProperty("nexira.android.compileSdk").map(String::toInt).getOrElse(37)
            minSdk = 31
            compilerOptions { jvmTarget.set(JvmTarget.JVM_25) }
        }

        kotlin.sourceSets.apply {
            val commonMain = getByName("commonMain")
            val jvmSharedMain = create("jvmSharedMain") { dependsOn(commonMain) }
            getByName("jvmMain").dependsOn(jvmSharedMain)
            getByName("androidMain").dependsOn(jvmSharedMain)
            getByName("jvmTest").dependencies { implementation("org.jetbrains.kotlin:kotlin-test") }
        }

        project.tasks.withType(Test::class.java).configureEach { useJUnitPlatform() }
    }
}

/** What a look module's build says beyond what [LookModulePlugin] gives every one. */
abstract class LookModuleExtension(
    private val project: Project,
    private val kotlin: KotlinMultiplatformExtension,
    private val names: LookModuleNames,
) {
    /** The Android namespace, derived from the module's name. */
    val namespace: String get() = names.namespace

    /** The Gradle property this module's API version is kept under, derived from the module's name. */
    val versionProperty: String get() = names.versionProperty

    /**
     * Marks the module as an API other modules compile against. Its public surface is dumped under
     * `api/` and checked by `check`, and its version, the Gradle property [versionProperty] as
     * `major.minor`, is handed to its tests under the same name, for the test that holds the API's own
     * version constant equal to it.
     */
    @OptIn(ExperimentalAbiValidation::class)
    fun api() {
        val property = names.versionProperty
        val version = project.providers.gradleProperty(property).orNull
            ?: throw GradleException("${project.path} is an API, and gradle.properties has no $property for its version")
        if (!Regex("""\d+\.\d+""").matches(version)) throw GradleException("${project.path}: $property=$version is not major.minor")
        kotlin.abiValidation()
        project.tasks.withType(Test::class.java).configureEach { systemProperty(property, version) }
    }
}

/** The names a look module's own derive from: [module] is its project name, `colour-nx` or `manager-api`. */
class LookModuleNames(private val module: String) {
    init {
        require(Regex("[a-z][a-z0-9]*(-[a-z][a-z0-9]*)*").matches(module)) { "look module name '$module' is not lower case words joined by dashes" }
    }

    /** `hivens.look.` and the name with its dashes as dots: `hivens.look.manager.api`. */
    val namespace: String get() = "hivens.look." + module.replace('-', '.')

    /** `nexira.look`, the name without an `-api` suffix in camel case, and `Api`: `nexira.lookManagerApi`. */
    val versionProperty: String
        get() = "nexira.look" + module.removeSuffix("-api").split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + "Api"
}
