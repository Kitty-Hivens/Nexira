import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Only for compose.desktop.currentOs on the test classpath: the module itself
    // ships no resources and no desktop entry point.
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.android.kmp.library)
}

// What sits above a window's own content and in what order, and what Escape and
// back close: layer ordering, input blocking under a modal, outside-press
// dismissal and the dismiss stack. No visuals: no colours, shapes or design
// tokens, so both nx-ui (which draws popups) and widget-api (which a module jar
// compiles against) can depend on it without depending on each other.
kotlin {
    // A KMP module has no `java` plugin, so the root's toolchain pin skips it and
    // org.gradle.jvm.version is matched to the JDK 27 modules here.
    jvmToolchain(27)

    // widget-api will expose it to module jars, so its public surface is held to
    // the dump in api/ the way that module's is.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    jvm()
    android {
        namespace = "hivens.ui.layers"
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
                // KeyEvent and Modifier are in the signatures a window host calls.
                api(libs.compose.ui)
                implementation(libs.compose.foundation)
            }
        }

        getByName("jvmMain") {
            dependencies {
                // The desktop window's back dispatcher, where the stack registers so a
                // Compose popup or dialog opened later is asked before it.
                implementation(libs.navigationevent.compose)
            }
        }

        getByName("jvmTest") {
            dependencies {
                // A KMP module has no `java` plugin for the root to pin JUnit on.
                implementation(project.dependencies.platform(libs.junit.bom))
                implementation(kotlin("test"))
                // Skiko natives for ImageComposeScene.
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
