import hivens.android.launcherApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.ksp)
}

// The pages module: the screens the modules bring, the ones a person made, which screen a
// composition starts on, and the pages host, the widget that draws the page shown with a
// transition between pages. What is shown and where back leads is the history module's,
// which requires this one. A document that brings in neither draws no pages at all.
// Compiled into the application for now and handed to the host as a CompiledModule.
kotlin {
    // KMP modules don't apply the `java` plugin, so the root's toolchain pin skips
    // them. Set here, org.gradle.jvm.version matches the JDK 27 modules.
    jvmToolchain(27)

    jvm()
    android {
        namespace = "hivens.pages"
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
                api(project(":pages-api"))
                api(project(":widget-api"))
                implementation(project(":nx-ui"))
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
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

// The host's registry, one per target, under a name of this module's own: two modules emitting
// the processor's default object would collide on the classpath and one would lose its widgets.
dependencies {
    add("kspJvm", project(":widget-processor"))
    add("kspAndroid", project(":widget-processor"))
}

ksp {
    arg("widgetRegistryPackage", "hivens.pages.generated")
    arg("widgetRegistryName", "PagesWidgetRegistry")
}
