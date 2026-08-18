plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// A widget module that is not client-ui. It exists to prove the kernel accepts
// widgets from outside the single generated registry, so it deliberately
// depends on neither client-ui nor nx-ui: no NxTheme, no design tokens, no
// shared primitives. What it draws, it draws itself.
dependencies {
    implementation(project(":widget-model"))
    implementation(project(":widget-api"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.kotlinx.serialization.json)
    // Decode and playback. The same engine client-ui's AudioPlayer uses, taken
    // directly rather than through it -- a module reaching into the trunk for a
    // service would defeat the point of the exercise.
    implementation(libs.skinema.compose)

    ksp(project(":widget-processor"))

    testImplementation(kotlin("test"))
    // Skiko natives for the current OS so an off-screen ImageComposeScene can
    // make a Skia surface. Test-only: the published module carries no
    // compose.desktop dependency.
    testImplementation(compose.desktop.currentOs)
}

// Its own registry object. Two modules emitting hivens.widget.generated.
// GeneratedWidgetRegistry would collide on the classpath and one would lose its
// widgets silently, which is what these options exist to prevent.
ksp {
    arg("widgetRegistryPackage", "hivens.module.pixelplayer.generated")
    arg("widgetRegistryName", "PixelPlayerWidgetRegistry")
}

tasks.test {
    useJUnitPlatform()
}
