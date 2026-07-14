plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    id("nexira.native-image")
}

// Compose-free, headless entrypoint over the launch pipeline. This is the
// module that gets compiled to a GraalVM / Liberica-NIK native binary --
// it must NOT pull in :client-ui, Compose, Skiko, AWT, or FileKit, or the
// native image inherits the Skiko/AWT wall that blocks the GUI.
dependencies {
    implementation(project(":client-core"))
    implementation(project(":client-config"))
    implementation(project(":client-launcher"))
    implementation(project(":client-auth"))
    implementation(project(":client-auth-smartycraft"))
    implementation(project(":client-auth-microsoft"))

    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    // slf4j-simple (-> stderr) rather than logback as the CLI logging backend:
    // logback needs build-time-init config + Joran reflection metadata under
    // native-image; slf4j-simple is a single static binding with none of that.
    runtimeOnly(libs.slf4j.simple)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("hivens.cli.MainKt")
}

// GraalVM / Liberica-NIK native binary. Toolchain auto-detects via GRAALVM_HOME
// (or /usr/lib/jvm scan / PATH); mainClass inherits from `application` above.
// See docs/native-image.md.
nativeImage {
    imageName.set("nexira-cli")
}
