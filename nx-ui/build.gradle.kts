import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// Leaf design-system module (NxUI). Holds tokens, primitives, surfaces and the
// Flexible decorator layer that the rest of the UI builds on. Depends on nothing
// in-tree -- client-ui depends on it one-way. No application/packaging here, no
// puppet source-set, no client-core: this is the bottom of the UI graph.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

group = "hivens"

// Repositories live in settings.gradle.kts (FAIL_ON_PROJECT_REPOS); no block here.

kotlin {
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.ui)
                implementation(libs.compose.components.resources)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.material.color.utilities)
                implementation(libs.slf4j.api)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// Own composeResources -- the icon + typography fonts live here. The generated
// Res accessor uses a DIFFERENT package than client-ui's (hivens.ui.generated.resources)
// so the two Res objects never collide on the classpath.
compose.resources {
    publicResClass = true
    packageOfResClass = "hivens.nx.ui.generated.resources"
    generateResClass = always
}

// jvmTarget is also forced project-wide by the root subprojects {} block; set it
// explicitly here too, matching client-ui, so the module is correct in isolation.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}
