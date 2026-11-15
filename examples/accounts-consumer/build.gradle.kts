plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose.compiler)
}

// A worked example of a module built on the accounts module's API, and not part of the
// launcher. It compiles against the widget kernel and the accounts API artifact and
// nothing else in-tree: no client-ui, no client-core, no accounts module. It reads who
// the launcher is signed in as and fires sign-out, typed, and answers a key of its own
// made from the session, so the build itself proves a module needs nothing more, and
// that what it reads carries no secret it could not have compiled against anyway.
dependencies {
    api(project(":widget-api"))
    implementation(project(":accounts-api"))
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
            "Nexira-Module-Id" to "accounts-consumer",
            "Nexira-Module-Name" to "Accounts consumer",
            // The accounts API this jar was compiled against, which the loader checks
            // the launcher's against before it opens the jar.
            "Nexira-Module-Requires" to "nexira.accounts=" + providers.gradleProperty("nexira.accountsApi").get(),
        )
    }
}
