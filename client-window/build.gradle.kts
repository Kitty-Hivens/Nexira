plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // OS detection and the atomic file write the window record is saved with.
    implementation(project(":client-core"))
    // The window's state is published as StateFlows, so callers see the type.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
