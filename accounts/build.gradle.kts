import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// The accounts and sign-in module: answers the keys :accounts-api names, over the
// launcher's credential store and its sign-in providers. It brings them through the
// widget kernel's key contribution, the way a module jar does, so a document that does
// not name it has none of them. Compiled into the application for now and handed to
// the host as a CompiledModule.
//
// It knows the engine's credential store and provider contract (client-core,
// client-auth) and the kernel, and never client-ui, client-launcher or a concrete
// provider: providers are found in the registry by key and by what they can do. What
// it needs from the application around it, a place to say what failed, the writer for
// the person's choices and the certificate question, comes in through AccountsHost,
// which the host implements.
kotlin {
    // KMP modules don't apply the `java` plugin, so the root's toolchain pin skips
    // them. Set here, org.gradle.jvm.version matches the JDK 27 modules.
    jvmToolchain(27)

    jvm()
    android {
        namespace = "hivens.accounts"
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
                api(project(":accounts-api"))
                api(project(":widget-api"))
                // The session with its tokens is what the host and the modules it trusts
                // are handed, so the types it is made of are part of this module's face.
                api(project(":client-core"))
                implementation(project(":client-auth"))
                implementation(libs.slf4j.api)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.mockk)
                // The startup sign-in's tests refresh through the real Microsoft provider's type.
                implementation(project(":client-auth-microsoft"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
