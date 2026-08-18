plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// A worked example of a widget module, and not part of the launcher.
//
// It is what a third party would write: it depends on the widget kernel and on
// nothing else in-tree -- no client-ui, no nx-ui, no NxTheme, no design tokens.
// What it draws, it draws itself. The launcher never compiles against it; the
// jar this produces is discovered from the widgets directory at boot.
//
// Everything below except the two ksp options and the manifest is an ordinary
// Kotlin/Compose module, which is the point.
dependencies {
    api(project(":widget-model"))
    api(project(":widget-api"))
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
    // The packaging test reads this module's own jar, so it has to exist first.
    dependsOn(tasks.jar)
    systemProperty("nexira.test.moduleJar", tasks.jar.flatMap { it.archiveFile }.get().asFile.absolutePath)
}

// What the loader reads before it opens a single class. The API version is the
// honest half: a module compiled against a different widget kernel may link and
// then misbehave, so the launcher refuses it by version rather than finding out
// on a frame. Keep it in step with hivens.widget.api.WidgetApi.VERSION.
tasks.jar {
    manifest {
        attributes(
            "Nexira-Widget-Api" to 1,
            "Nexira-Module-Id" to "pixelplayer",
            "Nexira-Module-Name" to "Pixel Player",
        )
    }
}

// Drops the built jar where a running launcher will find it, which is the whole
// install procedure: there is no registry to update and nothing to unpack.
// Override the destination with -PwidgetsDir=... on platforms whose data
// directory is not the XDG one.
tasks.register<Copy>("installWidget") {
    group = "nexira"
    description = "Copy this widget module into the launcher's widgets directory"
    from(tasks.jar)
    into(
        providers.gradleProperty("widgetsDir").orElse(
            providers.systemProperty("user.home").map { "$it/.local/share/nexira/widgets" },
        ),
    )
}
