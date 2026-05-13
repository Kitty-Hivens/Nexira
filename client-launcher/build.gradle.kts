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
    // No JNA here on purpose — Vault keyring + future native bindings use
    // Project Panama (java.lang.foreign.*, JEP 454, finalized Java 22).
    // dorkbox/SystemTray's JNA dependency (in client-ui) is the only
    // remaining JNA user; that goes away when dorkbox-replace lands.

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
        // Both tags are dev / maintenance only — neither belongs in the
        // regular unit-test set. `smoke` hits real smartycraft.ru;
        // `live-keyring` hits the developer's own Secret Service daemon.
        excludeTags("smoke", "live-keyring")
    }
}

// Local-only task for live keyring probe against the developer's Secret
// Service daemon. Skipped automatically (via assumeTrue) when the daemon
// isn't reachable; never wired into CI because GitHub-hosted runners
// don't have a desktop session.
//
// `--enable-native-access=ALL-UNNAMED` is mandatory for Project Panama
// (java.lang.foreign.*) — without it Linker.nativeLinker().downcallHandle
// throws IllegalCallerException on JDK 22+. The same flag is baked into
// the AppImage AppRun via scripts/build-appimage.sh, so production runs
// have it too.
val liveKeyringTest by tasks.registering(Test::class) {
    description = "Runs the LinuxLibsecretKeyringStorage live probe against the local Secret Service daemon."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("live-keyring")
    }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    outputs.upToDateWhen { false }
    shouldRunAfter(tasks.test)
}

// Same flag for the regular test task — Vault unit tests construct
// LinuxLibsecretKeyringStorage even when isAvailable() ends up false,
// and Panama symbol lookup happens at construction time.
tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
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
