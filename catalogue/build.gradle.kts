import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// The catalogue module: answers the keys :catalogue-api names, over the sources the
// modules bring, which it takes as contributions of the API's source contract and names
// none of, and over the launcher's engine for what is not a source's yet: the project and
// build pages and the installs that put a project into a pack. It brings its keys through
// the widget kernel's key contribution, the way a module jar does, so a document that does
// not name it has none of them. Compiled into the application for now and handed to the
// host as a CompiledModule.
//
// It knows the engine (client-core, client-launcher) and the kernel, and never client-ui
// or a source module: what a person reads is put into words by the widgets, and the module
// hands them plain data. Installing a whole pack is the packs module's command, which a
// widget fires with the build a pack page names.
kotlin {
    // KMP modules don't apply the `java` plugin, so the root's toolchain pin skips
    // them. Set here, org.gradle.jvm.version matches the JDK 27 modules.
    jvmToolchain(27)

    jvm()
    android {
        namespace = "hivens.catalogue"
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
                api(project(":catalogue-api"))
                api(project(":widget-api"))
                implementation(project(":client-core"))
                implementation(project(":client-launcher"))
                implementation(libs.slf4j.api)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                // The tests read the catalogue's own answers from fixtures.
                implementation(libs.kotlinx.serialization.json)
                // An offline catalogue client is stood up over the engine's HTTP type.
                implementation(libs.ktor.client.core)
                // A source of the test's own installs and updates over a mock engine.
                implementation(libs.ktor.client.mock)
                implementation(project(":client-core-testing"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
