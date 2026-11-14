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
// never client-ui: what it needs from the application around it, the session and the
// launch notices, comes in through PacksHost, which the host implements.
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
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
