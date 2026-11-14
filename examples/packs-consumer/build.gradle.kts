plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose.compiler)
}

// A worked example of a module built on another module's API, and not part of the
// launcher. It compiles against the widget kernel and the packs module's API artifact
// and nothing else in-tree: no client-ui, no client-core, no packs module. It reads and
// fires the pack keys typed, and answers a key of its own made from them, so the build
// itself proves a module needs nothing more to use the packs.
dependencies {
    api(project(":widget-api"))
    implementation(project(":packs-api"))
    implementation(libs.compose.runtime)

    testImplementation(kotlin("test"))
    // The packaging test opens this module's own jar the way the launcher does.
    testImplementation(project(":widget-loader"))
    // Skiko natives for the current OS so an off-screen ImageComposeScene can draw the
    // widget. Test-only: the module carries no compose.desktop dependency.
    testImplementation(compose.desktop.currentOs)
}

tasks.test {
    useJUnitPlatform()
    // The packaging test reads this module's own jar, so it has to exist first.
    dependsOn(tasks.jar)
    systemProperty("nexira.test.moduleJar", tasks.jar.flatMap { it.archiveFile }.get().asFile.absolutePath)
}

tasks.jar {
    manifest {
        attributes(
            "Nexira-Widget-Api" to providers.gradleProperty("nexira.widgetApi").get(),
            "Nexira-Module-Id" to "packs-consumer",
            "Nexira-Module-Name" to "Packs consumer",
            // The packs API this jar was compiled against, which the loader checks the
            // launcher's against before it opens the jar.
            "Nexira-Module-Requires" to "nexira.packs=" + providers.gradleProperty("nexira.packsApi").get(),
        )
    }
}
