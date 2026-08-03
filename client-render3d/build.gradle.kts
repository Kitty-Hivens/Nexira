import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// Software 3D. Three layers, bottom-up: render3d rasterises triangles into a
// bitmap, scene3d is the camera / projection / transform / scene-graph on top of
// it, skin3d is the Minecraft-specific rig -- body parts, poses, cape.
//
// Depends on nx-ui for one token (LocalStyle, which decides the animation
// multiplier) and on nothing else in-tree. Compose runtime + ui for the
// composable host and the ImageBitmap type; no material3, no client-core.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose.compiler)
}

group = "hivens"

// Repositories live in settings.gradle.kts (FAIL_ON_PROJECT_REPOS); no block here.

kotlin {
    // KMP modules don't apply the `java` plugin, so the root's toolchain pin
    // (java-plugin modules only) skips them; set it here so org.gradle.jvm.version
    // matches the JDK 26 leaf modules instead of falling back to the daemon JVM.
    jvmToolchain(26)
    jvm("desktop")

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.ui)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        getByName("desktopMain") {
            dependencies {
                implementation(project(":nx-ui"))
            }
        }

        getByName("desktopTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                // Skiko native runtime (current OS) so the render tests can create
                // a Skia surface. Test-only -- the module itself stays free of any
                // compose.desktop dependency.
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// jvmTarget is also forced project-wide by the root subprojects {} block; set it
// explicitly here too, matching nx-ui, so the module is correct in isolation.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_26)
    }
}
