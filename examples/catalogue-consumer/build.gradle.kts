plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose.compiler)
}

// A worked example of a module built on the catalogue module's API, and not part of the
// launcher. It compiles against the widget kernel and the catalogue API artifact and
// nothing else in-tree: no client-ui, no client-launcher, no catalogue module. It asks the
// window's search for words, reads what it found, typed, and answers a key of its own made
// from the results, so the build itself proves a module needs nothing more to search.
dependencies {
    api(project(":widget-api"))
    implementation(project(":catalogue-api"))
    implementation(libs.compose.runtime)

    testImplementation(kotlin("test"))
    // Skiko natives for the current OS so an off-screen ImageComposeScene can draw the
    // widget. Test-only: the module carries no compose.desktop dependency.
    testImplementation(compose.desktop.currentOs)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes(
            "Nexira-Widget-Api" to providers.gradleProperty("nexira.widgetApi").get(),
            "Nexira-Module-Id" to "catalogue-consumer",
            "Nexira-Module-Name" to "Catalogue consumer",
            // The catalogue API this jar was compiled against, which the loader checks the
            // launcher's against before it opens the jar.
            "Nexira-Module-Requires" to "nexira.catalogue=" + providers.gradleProperty("nexira.catalogueApi").get(),
        )
    }
}
