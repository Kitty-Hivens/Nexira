plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

dependencies {
    implementation(project(":client-config"))

    // Logging
    implementation(libs.slf4j.api)

    // Ktor Client & Serialization
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // JSON
    implementation(libs.kotlinx.serialization.json)

    // Archive extraction with unix-mode-aware ZipArchiveEntry -- needed by
    // ZipUtils to reject symlink entries that bypass plain Zip Slip checks.
    implementation(libs.commons.compress)

    // TEST
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.slf4j.simple)

    testFixturesImplementation(libs.ktor.client.core)
    testFixturesImplementation(libs.ktor.client.mock)
    testFixturesImplementation(libs.ktor.client.content.negotiation)
    testFixturesImplementation(libs.ktor.serialization.json)
}

tasks.test {
    useJUnitPlatform()
}
