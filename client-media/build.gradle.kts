plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // HttpClientProvider + core.platform (OS/Arch) -- the module's only in-tree
    // dependency; it knows nothing about the launch pipeline or the UI.
    implementation(project(":client-core"))

    implementation(libs.slf4j.api)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
}
