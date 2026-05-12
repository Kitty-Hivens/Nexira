plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":client-core"))
    implementation(project(":client-config"))

    implementation(libs.commons.compress)
    implementation(libs.koin.core)
    implementation(libs.slf4j.api)

    // Ktor Client & Serialization
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // ─── TEST ────────────────────────────────────────────────────────────────
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.slf4j.simple)

    // MockClientFactory and other test utilities from client-core
    testImplementation(testFixtures(project(":client-core")))
}

tasks.test {
    useJUnitPlatform {
        excludeTags("smoke")
    }
}

// ── Live smoke tests — gated on real `smartycraft.ru` ────────────────────────
//
// Lives in the same `test` source set under `hivens.launcher.smoke.*`,
// segregated from the regular suite by the `@SmokeTest` JUnit Tag so that
// `:client-launcher:test` excludes them and `:client-launcher:smokeTest`
// runs only them. Reads `SMARTY_TEST_USER` / `SMARTY_TEST_PASS` from env;
// `Assumptions.assumeTrue` short-circuits to "skipped" when absent, so
// running this task locally without secrets does not fail.
//
// `outputs.upToDateWhen { false }` because the *point* of this task is to
// probe live upstream state — caching a green "smoke passed" result against
// unchanged inputs would defeat the purpose entirely.
val smokeTest by tasks.registering(Test::class) {
    description = "Runs live smoke tests against real smartycraft.ru. Requires SMARTY_TEST_USER/PASS env."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("smoke")
    }
    outputs.upToDateWhen { false }
    shouldRunAfter(tasks.test)
}
