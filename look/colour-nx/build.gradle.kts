import hivens.android.launcherApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // For the tests only: the shots of the design system's primitives in every theme that
    // ships draw off screen, which takes the Skia runtime of the machine running them.
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

// The themes module: the themes that ship, the one made from the wallpaper, the ones modules
// bring and the person's own, which of them is in use and what decides dark and light, and
// where all of that is kept. It answers the keys :look:colour-nx-api names. Compiled into the
// application. The module makes its manager itself, from the host's data directory, and
// answers the theme the window the launcher boots in is drawn in before the host is up,
// worked out from the same files. It reads the system's colour scheme itself, for the mode that
// follows it: on a desktop by asking the system's own tools (jvmMain), on a phone from the
// configuration of the application, whose context the host hands it (androidMain).
//
// It is also the look's nx provider of colour: it answers the colour axis of :look:colour-api
// from the theme in use, before the boot from the same files and once the host is up from its
// own manager, so the look manager opens it like any provider and knows nothing of themes.
//
// Drawing is not here: nx-ui draws from the colour axis's answers and knows no theme. A scheme
// answers them in :look:colour-nx-api, for this provider and for a preview alike. This module knows the widget kernel, its own API, the colour
// axis's API and the language module's API, whose words name the themes modules bring, and
// nothing of the launcher.
kotlin {
    // KMP modules don't apply the `java` plugin, so the root's toolchain pin skips
    // them. Set here, org.gradle.jvm.version matches the JDK 27 modules.
    jvmToolchain(27)

    jvm()
    android {
        namespace = "hivens.themes"
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
                api(project(":look:colour-nx-api"))
                api(project(":widget-api"))
                implementation(project(":look:colour-api"))
                implementation(project(":i18n-api"))
                implementation(libs.compose.runtime)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.material.color.utilities)
                implementation(libs.slf4j.api)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(compose.desktop.currentOs)
                // What draws the shots beyond the theme they are of.
                implementation(project(":nx-ui-testing"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
