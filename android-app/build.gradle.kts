import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
}

/**
 * The version code, from the time of the commit in minutes.
 *
 * Android installs an update only over a lower code. The commit count cannot give
 * that: dev and stable have different histories, so a release cut on stable would
 * count fewer commits than a nightly cut from dev before it. Time grows on both.
 */
fun commitMinutes(providers: ProviderFactory): Int = try {
    providers.exec { commandLine("git", "log", "-1", "--format=%ct") }
        .standardOutput.asText.get().trim().toLong().div(60).toInt()
} catch (e: Exception) {
    println("Git commit time lookup failed: ${e.message}")
    1
}

// The release key comes in through the environment, as CI hands it over from the
// repository's secrets. Without it the release APK is built unsigned.
val releaseKeystore: String? = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull

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
        versionCode = commitMinutes(providers)
        versionName = project.version.toString()
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
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
    implementation(libs.touchcontroller.proxy.android)
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
