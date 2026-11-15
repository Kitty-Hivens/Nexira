import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// What another module compiles against to read and fire the catalogue module's keys:
// the key ids as constants, the typed keys, the plain data they carry and the rule
// that folds game versions into ranges, which every reader of a version list needs
// to agree on. Also what a module implements to bring a source the catalogue lists
// from: the source contributions, which need the widget kernel and so live in the JVM
// source set. No launcher type. The module that answers the keys and takes the
// sources is :catalogue.
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
        namespace = "hivens.catalogue.api"
        compileSdk = providers.gradleProperty("nexira.android.compileSdk").map(String::toInt).getOrElse(37)
        minSdk = 31
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    sourceSets {
        val jvmSharedMain = create("jvmSharedMain") {
            dependsOn(getByName("commonMain"))
        }
        getByName("jvmMain").dependsOn(jvmSharedMain)
        getByName("androidMain").dependsOn(jvmSharedMain)

        getByName("commonMain") {
            dependencies {
                api(project(":widget-model"))
            }
        }

        getByName("jvmSharedMain") {
            dependencies {
                api(project(":widget-api"))
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // What a module build stamps into its manifest, for the test that holds it equal
    // to CatalogueApi.VERSION.
    systemProperty("nexira.catalogueApi", providers.gradleProperty("nexira.catalogueApi").get())
}
