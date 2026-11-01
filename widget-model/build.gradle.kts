import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

// Pure data module. No Compose, no UI dep, no @Composable. Consumers
// that only want the data shape (launcher persistence, future CLI /
// TUI surfaces that never touch Compose) depend on this module;
// Compose-tainted bits live in :widget-api one layer up.
//
// Everything is common code except the two objects that read the bundled
// layout JSON out of the classpath, which sit with their resources in the
// source set both JVM targets share.
kotlin {
    // KMP modules don't apply the `java` plugin, so the root's toolchain pin
    // skips them; set it here so org.gradle.jvm.version matches the JDK 27 modules.
    jvmToolchain(27)
    jvm()
    android {
        namespace = "hivens.widget.model"
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

        getByName("commonMain") {
            dependencies {
                // api (not implementation): WidgetInstance.props is a JsonObject and
                // WidgetDescriptor in :widget-api now exposes KSerializer +
                // JsonObject for typed props, so the serialization types must be on
                // the compile classpath of modules that depend on :widget-model.
                api(libs.kotlinx.serialization.json)
                // api (not implementation): WidgetDataSource.state is a StateFlow on the
                // public contract, so downstream modules (widget-api, client-ui) need the
                // coroutines types on their compile classpath. Stays Compose-free.
                api(libs.kotlinx.coroutines.core)
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
