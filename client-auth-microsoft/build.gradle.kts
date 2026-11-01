import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    // KMP modules don't apply the `java` plugin, so the root's toolchain pin
    // skips them; set it here so org.gradle.jvm.version matches the JDK 27 modules.
    jvmToolchain(27)
    jvm()
    android {
        namespace = "hivens.auth.microsoft"
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

        getByName("jvmSharedMain") {
            dependencies {
                // SessionData / AuthStatus / AuthException / HttpClientProvider live in core.
                implementation(project(":client-core"))
                // AuthProvider SPI + DeviceCodeAuthProvider capability interface.
                implementation(project(":client-auth"))

                implementation(libs.slf4j.api)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.client.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.ktor.client.mock)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.slf4j.simple)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
