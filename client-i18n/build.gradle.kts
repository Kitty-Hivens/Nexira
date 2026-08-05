import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// Localisation layer. Holds the AppStrings interface, the English / Russian /
// German implementations, the AppLocale enum and the LocalStrings
// CompositionLocal. Compose runtime only -- no foundation, no material3, no
// desktop: nothing here draws.
//
// The one dependency into the tree is client-core, for the provider key a pack's
// auth-requirement string switches on. It carries no reverse edge: nothing under
// hivens.core, hivens.launcher or nx-ui names AppStrings.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
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
                implementation(libs.kotlinx.serialization.json)
            }
        }

        getByName("desktopMain") {
            dependencies {
                implementation(project(":client-core"))
            }
        }

        getByName("desktopTest") {
            dependencies {
                implementation(kotlin("test"))
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
