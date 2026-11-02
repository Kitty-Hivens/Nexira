import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
}

android {
    namespace = "dev.hivens.nexira"
    // Compose 1.13 for Android is built against API 37.1, so the app compiles against that and targets 37.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "dev.hivens.nexira"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "probe"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // The shim that starts the game's JVM in this process. The NDK is the one the JDK
    // itself was built with, so both ends of the call agree on the C library.
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

dependencies {
    implementation(project(":client-playback"))
    implementation(project(":client-auth"))
    implementation(project(":client-core"))
    implementation(project(":client-launcher"))
    implementation(project(":client-ui"))
    implementation(libs.libvault.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.activity.compose)
    implementation(libs.filekit.dialogs.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.koin.core)
    implementation(libs.slf4j.api)
    // The probe wants the shared code's own logging in logcat: slf4j has no binding on
    // Android, and this one writes to System.err, which logcat carries.
    implementation(libs.slf4j.simple)
}
