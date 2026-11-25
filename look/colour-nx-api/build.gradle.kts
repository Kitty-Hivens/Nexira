import hivens.android.launcherApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

// What another module compiles against to read which theme the launcher is drawn in and
// to change it: the themes module's keys and settings, the theme a module brings, and the
// input the host feeds the wallpaper's tone through, and a theme itself with what its
// colours answer the colour axis. The widget kernel carries the keys. The module that
// answers is :look:colour-nx.
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
        namespace = "hivens.themes.api"
        compileSdk { launcherApi(providers) }
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

        getByName("jvmSharedMain") {
            dependencies {
                api(project(":widget-api"))
                // The colour axis's local, which a preview of a theme answers for what it wraps.
                api(project(":look:colour-api"))
                implementation(libs.compose.runtime)
                implementation(libs.material.color.utilities)
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
    // to ThemesApi.VERSION.
    systemProperty("nexira.themesApi", providers.gradleProperty("nexira.themesApi").get())
}
