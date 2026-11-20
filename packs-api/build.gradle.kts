import hivens.android.launcherApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// What another module compiles against to read and fire the packs module's keys: the
// key ids as constants, the typed keys and the plain data they carry. No Compose and
// no launcher type, so a widget module needs this and the widget kernel and nothing
// else. The module that answers the keys is :packs.
kotlin {
    // KMP modules don't apply the `java` plugin, so the root's toolchain pin skips
    // them. Set here, org.gradle.jvm.version matches the JDK 27 modules.
    jvmToolchain(27)

    // A module jar is compiled against this and refused by its version when it
    // changes, so every change to the public surface has to show in the dump in api/.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    jvm()
    android {
        namespace = "hivens.packs.api"
        compileSdk { launcherApi(providers) }
        minSdk = 31
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":widget-model"))
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // What a module build stamps into its manifest, for the test that holds it equal
    // to PacksApi.VERSION.
    systemProperty("nexira.packsApi", providers.gradleProperty("nexira.packsApi").get())
}
