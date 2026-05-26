plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Pure data module. No Compose, no UI dep, no @Composable. Consumers
// that only want the data shape (launcher persistence, future CLI /
// TUI surfaces that never touch Compose) depend on this module;
// Compose-tainted bits live in :widget-api one layer up.
dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
