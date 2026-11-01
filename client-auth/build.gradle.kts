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
        namespace = "hivens.auth"
        compileSdk = providers.gradleProperty("nexira.android.compileSdk").map(String::toInt).getOrElse(36)
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
                // SessionData, AuthStatus, AuthException/TwoFactorRequiredException,
                // IServerProtocol + protocol models, HashUtils, retryWithBackoff -- all stay
                // in core; this module sits above core and stays SmartyCraft-agnostic.
                implementation(project(":client-core"))

                implementation(libs.slf4j.api)
                implementation(libs.kotlinx.coroutines.core)
                // credentials.json (the non-secret account list) is a @Serializable envelope.
                implementation(libs.kotlinx.serialization.json)
                // api (not implementation): SecretVault sits in CredentialsManager's public
                // constructor, so the assembler constructing it needs the type.
                api(libs.libvault.core)

                // ktor-io's ClosedByteChannelException is one of the transient-network
                // failures the agnostic retry classifier recognises.
                implementation(libs.ktor.client.core)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.mockk)
                implementation(libs.slf4j.simple)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
