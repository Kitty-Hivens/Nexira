import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// The April Fools engine and its companions: a self-contained seasonal surface
// that decorates the shell on one day of the year and is inert on every other.
//
// Its whole contract with the rest of the tree is three symbols -- NxTheme and
// NxSwitch from nx-ui, LocalStrings from client-i18n -- so it sits here rather
// than inside client-ui, where a seasonal prank shared a module with the launch
// path. Nothing in the tree may depend on it except the shell that mounts it.
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
                implementation(libs.compose.material3)
                implementation(libs.compose.ui)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        getByName("desktopMain") {
            dependencies {
                implementation(project(":nx-ui"))
                implementation(project(":client-i18n"))
            }
        }

        getByName("desktopTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// jvmTarget is also forced project-wide by the root subprojects {} block; set it
// explicitly here too, matching nx-ui, so the module is correct in isolation.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_26)

        // Language: AprilFoolsEngine nests a typealias for the floating-button
        // event signature. The flag travelled here with the file; Kotlin 2.4
        // accepts the nesting without it, but the declaration outlives any one
        // compiler version and the module should not depend on that default.
        freeCompilerArgs.add("-XXLanguage:+NestedTypeAliases")
    }
}
