import hivens.android.launcherApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// The Hivens mirror as a source of the catalogue: its listing, its own packs and its
// community's, a pack's page and its builds, in the catalogue API's plain values. It
// brings them as a PackSource contribution, so a document that does not name it lists
// nothing from the mirror. Compiled into the application and handed to the host as a
// CompiledModule.
//
// It also brings what the mirror does to the packs it installs, on what its pack source
// opens: a build synced from its manifest, updates, reattach and repair against the
// manifests it publishes per build, and the pack's cover. It reads the mirror through
// the engine's client (client-launcher), which the launch, the content tab and the pack
// screens still share with it, and never knows the catalogue module, client-ui or another
// source. Nothing compiles against it, so it has no API artifact of its own: the catalogue
// finds it by its contribution, and the engine installs, updates and reattaches through
// the same contract.
kotlin {
    // KMP modules don't apply the `java` plugin, so the root's toolchain pin skips
    // them. Set here, org.gradle.jvm.version matches the JDK 27 modules.
    jvmToolchain(27)

    jvm()
    android {
        namespace = "hivens.source.mirror"
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
                implementation(project(":catalogue-api"))
                implementation(project(":widget-api"))
                implementation(project(":client-core"))
                implementation(project(":client-launcher"))
                implementation(libs.slf4j.api)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.serialization.json)
                // The mirror's answers are served by a mock engine under the engine's HTTP client.
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.mock)
                // The engine's transfers over the mock engine, for an install and an update run end to end.
                implementation(project(":client-core-testing"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
