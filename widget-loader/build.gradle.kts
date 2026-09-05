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
}

// A widget module built the way a third party would build one, kept OFF the test
// classpath on purpose: a fixture the tests could already see would resolve
// through the parent loader and the tests would pass without the jar being
// opened. The tests rewrite its manifest to produce the malformed variants.
val fixtureModule: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].output + configurations["compileClasspath"].incoming.files
}

val fixtureModuleJar by tasks.registering(Jar::class) {
    archiveFileName.set("fixture-module.jar")
    destinationDirectory.set(layout.buildDirectory.dir("fixtures"))
    from(fixtureModule.output)
    manifest {
        attributes(
            "Nexira-Widget-Api" to 1,
            "Nexira-Module-Id" to "fixture",
            "Nexira-Module-Name" to "Fixture Module",
        )
    }
}

tasks.test {
    useJUnitPlatform()
    dependsOn(fixtureModuleJar)
    systemProperty("nexira.test.fixtureModuleJar", fixtureModuleJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
}
