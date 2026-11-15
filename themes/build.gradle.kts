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
// where all of that is kept. It answers the keys :themes-api names. Compiled into the
// application. The host makes its manager before any module loads, since the window the
// launcher boots in is already drawn in the person's theme, and the module answers its keys
// over that one manager.
//
// Drawing is not here: a theme is drawn by nx-ui, which is handed the theme to draw and
// never knows where the choice is kept. It knows the widget kernel, its own API and the
// language module's API, whose words name the themes modules bring, and nothing of the
// launcher.
kotlin {
    // KMP modules don't apply the `java` plugin, so the root's toolchain pin skips
    // them. Set here, org.gradle.jvm.version matches the JDK 27 modules.
    jvmToolchain(27)

    jvm()
    android {
        namespace = "hivens.themes"
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
                api(project(":themes-api"))
                api(project(":widget-api"))
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
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
