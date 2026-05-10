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
    useJUnitPlatform()
}
