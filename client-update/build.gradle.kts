// The launcher updating itself: version check, delta bundle, binary patch,
// install layout, and the per-platform applicators that swap the running
// installation and hand the process back.
//
// This is NOT pack update. The two shared a package (`hivens.launcher.update`)
// and nothing else -- pack update reads the mirror and an installed instance,
// this reads a GitHub release and the launcher's own install directory. Neither
// group referenced the other, and each even had its own `UpdateOutcome`.
// Pack update stays in client-launcher, next to the mirror client it calls.
//
// Depends on client-core (transfer engine, release manifest, platform) and
// client-config (branding, for the release repository). It names nothing under
// hivens.launcher, which is what makes it a module rather than a package.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":client-core"))
    implementation(project(":client-config"))

    // Delta updates: jbsdiff applies the bsdiff patch, commons-compress reads
    // the bundle around it.
    implementation(libs.jbsdiff)
    implementation(libs.commons.compress)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    // Release metadata and asset download go through the shared client.
    implementation(libs.ktor.client.core)

    // ─── TEST ────────────────────────────────────────────────────────────────
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.slf4j.simple)

    // MockClientFactory, testTransferEngine and the other shared fixtures.
    testImplementation(testFixtures(project(":client-core")))
}

tasks.test {
    useJUnitPlatform()
}
