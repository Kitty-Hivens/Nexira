import hivens.android.launcherApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

// What another module compiles against to read the launcher's words and the language
// they are in: the lookup a widget or a service makes by namespace and key, the
// composition local the host provides it through, and the language module's keys and
// settings. Compose runtime only, for the composable lookup, and the widget kernel, for
// the settings change the update command carries. The engine that answers is :i18n.
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
        namespace = "hivens.i18n.api"
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
                implementation(libs.compose.runtime)
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
    // to I18nApi.VERSION.
    systemProperty("nexira.i18nApi", providers.gradleProperty("nexira.i18nApi").get())
}
