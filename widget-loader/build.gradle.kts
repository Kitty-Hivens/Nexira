plugins {
    alias(libs.plugins.kotlin.jvm)
    // For the test fixture only: its registry carries a real WidgetDescriptor,
    // whose Render is composable. Nothing in main is.
    alias(libs.plugins.kotlin.compose.compiler)
}

// Finds widget modules on disk and turns them into registries the kernel can
// merge. Deliberately separate from :widget-api, which is what a module author
// compiles against: nothing in a module's own build should drag in the machinery
// that loads it.
dependencies {
    api(project(":widget-api"))
    implementation(libs.slf4j.api)
    // WidgetDescriptor.Render is composable, so the runtime is on the classpath
    // either way; naming it here is what lets the compose compiler plugin run.
    implementation(libs.compose.runtime)

    testImplementation(kotlin("test"))
    // The themes module's API, for the tests of a kind of contribution a module compiled into
    // the launcher declares rather than the kernel: the fixture brings a theme through it.
    testImplementation(project(":look:colour-nx-api"))
}

// A widget module built the way a third party would build one, kept OFF the test
// classpath on purpose: a fixture the tests could already see would resolve
// through the parent loader and the tests would pass without the jar being
// opened. The tests rewrite its manifest to produce the malformed variants.
val fixtureModule: SourceSet = sourceSets.create("fixtureModule") {
    compileClasspath += sourceSets["main"].output + configurations["compileClasspath"].incoming.files
}
dependencies {
    // The fixture brings a theme, which a module does through the themes module's API.
    "fixtureModuleImplementation"(project(":look:colour-nx-api"))
}

val fixtureModuleJar = tasks.register<Jar>("fixtureModuleJar") {
    archiveFileName.set("fixture-module.jar")
    destinationDirectory.set(layout.buildDirectory.dir("fixtures"))
    from(fixtureModule.output)
    manifest {
        attributes(
            "Nexira-Widget-Api" to providers.gradleProperty("nexira.widgetApi").get(),
            "Nexira-Module-Id" to "fixture",
            "Nexira-Module-Name" to "Fixture Module",
            // The themes API its theme is brought through. The loader's own tests write
            // the manifest again for each case, so this is what the launcher's tests see.
            "Nexira-Module-Requires" to "nexira.themes=" + providers.gradleProperty("nexira.themesApi").get(),
        )
    }
}

tasks.test {
    useJUnitPlatform()
    dependsOn(fixtureModuleJar)
    systemProperty("nexira.test.fixtureModuleJar", fixtureModuleJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
}
