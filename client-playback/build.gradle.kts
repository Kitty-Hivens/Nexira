import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// What decides how the launcher plays sound, with nothing under it that draws or
// decodes: the queue and its repeat modes, the state machine over an engine, the
// router that hands the session to the wallpaper, and the MusicPlayerService
// contract the player widgets read. The engines (skinema on the desktop, a platform
// player elsewhere) and the cover-art decoder are handed in from outside.
//
// The packages are the ones the code had in the UI module, so nothing that imports
// it had to change.
kotlin {
    // KMP modules don't apply the `java` plugin, so the root's toolchain pin
    // skips them; set it here so org.gradle.jvm.version matches the JDK 27 modules.
    jvmToolchain(27)
    jvm()
    android {
        namespace = "hivens.playback"
        compileSdk = providers.gradleProperty("nexira.android.compileSdk").map(String::toInt).getOrElse(36)
        minSdk = 31
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    sourceSets {
        val jvmSharedMain by creating {
            dependsOn(getByName("commonMain"))
        }
        getByName("jvmMain").dependsOn(jvmSharedMain)
        getByName("androidMain").dependsOn(jvmSharedMain)

        getByName("jvmSharedMain") {
            dependencies {
                // api: MusicPlayerService extends WidgetService.
                api(project(":widget-model"))
                // api: StateFlow is on the public contract.
                api(libs.kotlinx.coroutines.core)
                // api: TrackInfo.artwork is an ImageBitmap. Only the type is used, nothing here draws.
                api(libs.compose.ui)

                implementation(libs.slf4j.api)
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
}
