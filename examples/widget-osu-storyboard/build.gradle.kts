plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// A second worked example, written to answer one question: can a widget module
// built only against the public kernel carry a genuinely complex piece of
// motion, or does the API run out first.
//
// It renders an osu! storyboard: a declarative sprite timeline of fades, moves,
// scales, rotations, colour tints, blend changes and nested loops, read from a
// beatmap folder at runtime. Nothing here is compiled against the launcher, and
// the launcher is never compiled against it.
dependencies {
    api(project(":widget-model"))
    api(project(":widget-api"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.kotlinx.serialization.json)

    ksp(project(":widget-processor"))

    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.currentOs)
}

ksp {
    arg("widgetRegistryPackage", "hivens.module.osusb.generated")
    arg("widgetRegistryName", "OsuStoryboardWidgetRegistry")
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
    // Point this at a beatmap folder to run the parser against real content.
    providers.systemProperty("osusb.folder").orNull?.let { systemProperty("osusb.folder", it) }
}

tasks.jar {
    manifest {
        attributes(
            "Nexira-Widget-Api" to 1,
            "Nexira-Module-Id" to "osusb",
            "Nexira-Module-Name" to "osu! Storyboard",
        )
    }
}

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
