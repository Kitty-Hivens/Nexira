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
        namespace = "hivens.core"
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
                implementation(project(":client-config"))

                // Logging
                implementation(libs.slf4j.api)

                // Ktor Client & Serialization
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)

                // JSON
                implementation(libs.kotlinx.serialization.json)

                // Coroutines (Mutex / StateFlow / CompletableDeferred for the cache layer).
                // Was previously only transitive via ktor; depend on it explicitly.
                implementation(libs.kotlinx.coroutines.core)

                // Archive extraction with unix-mode-aware ZipArchiveEntry -- needed by
                // ZipUtils to reject symlink entries that bypass plain Zip Slip checks.
                implementation(libs.commons.compress)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.ktor.client.mock)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.mockk)
                implementation(libs.slf4j.simple)
                implementation(project(":client-core-testing"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
