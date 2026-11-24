import hivens.android.launcherApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// Modrinth as a source of the catalogue: its modpacks as a pack source (a pack's page
// and its builds), and its project search for mods, resource packs, shaders and modpacks
// with the lists the filters are built from, in the catalogue API's plain values. It
// brings them as PackSource and ProjectSearchSource contributions, so a document that
// does not name it searches nothing on Modrinth. Compiled into the application and
// handed to the host as a CompiledModule.
//
// It also brings what Modrinth does to the packs it installs, on what its pack source
// opens: a build's .mrpack installed through the engine's archive installer, updates and
// reattach through the engine's archive updater over Modrinth's version list, and the
// project's cover. It reads Modrinth through the engine's client (client-launcher), which
// the content tab and the project pages still share with it, and never knows the
// catalogue module, client-ui or another source. Nothing compiles against it, so it has
// no API artifact of its own.
kotlin {
    // KMP modules don't apply the `java` plugin, so the root's toolchain pin skips
    // them. Set here, org.gradle.jvm.version matches the JDK 27 modules.
    jvmToolchain(27)

    jvm()
    android {
        namespace = "hivens.source.modrinth"
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
                // Modrinth's answers are served by a mock engine under the engine's HTTP client.
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.mock)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.mockk)
                implementation(project(":client-core-testing"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
