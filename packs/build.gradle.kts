import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// The packs and launch module: answers the keys :packs-api names, over the launcher's
// engine. It brings them through the widget kernel's key contribution, the way a
// module jar does, so a document that does not name it has none of them. Compiled
// into the application for now and handed to the host as a CompiledModule.
//
// It knows the engine (client-core, client-launcher, client-auth) and the kernel, and
// never client-ui: what it needs from the application around it, the launch notices,
// comes in through PacksHost, which the host implements. Who a launch runs as is the
// accounts module's, read through the service it keeps for the modules the host trusts.
// A catalogue pack is installed from the source that lists it, as the catalogue module
// opened it and hands it to the modules the host trusts, by the catalogue API's contract.
kotlin {
    // KMP modules don't apply the `java` plugin, so the root's toolchain pin skips
    // them. Set here, org.gradle.jvm.version matches the JDK 27 modules.
    jvmToolchain(27)

    jvm()
    android {
        namespace = "hivens.packs"
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
                api(project(":packs-api"))
                api(project(":widget-api"))
                implementation(project(":accounts"))
                // What a pack is installed from: the sources the catalogue lists from.
                implementation(project(":catalogue-api"))
                implementation(project(":client-core"))
                implementation(project(":client-launcher"))
                implementation(project(":client-auth"))
                implementation(libs.slf4j.api)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                // The launch controller stands behind the pack actions and is not what a reattach test is about.
                implementation(libs.mockk)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
