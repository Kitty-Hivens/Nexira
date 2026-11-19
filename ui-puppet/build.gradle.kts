import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.android.kmp.library)
}

// The puppet hooks: the composables a control registers itself with, by id, for the puppet's
// HTTP control surface, and the registry they fill. A development seam, not part of the widget
// contract, so a module of its own rather than the kernel's or the design system's, which client-ui
// and the modules compiled into the launcher both use. No visuals and nothing but the Compose
// runtime: the server and its wire format stay in client-ui, built in only for a puppet build.
kotlin {
    // A KMP module has no `java` plugin, so the root's toolchain pin skips it and
    // org.gradle.jvm.version is matched to the JDK 27 modules here.
    jvmToolchain(27)

    jvm()
    android {
        namespace = "hivens.ui.puppet"
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

        getByName("jvmSharedMain") {
            dependencies {
                implementation(libs.compose.runtime)
            }
        }

        getByName("jvmTest") {
            dependencies {
                // A KMP module has no `java` plugin for the root to pin JUnit on.
                implementation(project.dependencies.platform(libs.junit.bom))
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
