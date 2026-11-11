plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Test doubles shared by the test source sets of :client-core and its consumers
// (fake server protocol, mock HTTP responses, a controllable clock, transfer
// engine helpers). A module of its own because java-test-fixtures has no
// equivalent in a Kotlin Multiplatform module. Test-only: never a dependency of
// anything that ships.
dependencies {
    implementation(project(":client-core"))

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.mock)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
