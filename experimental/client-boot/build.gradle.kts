plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// The floor. Everything else in the launcher is a module loaded onto this, so
// this module depends on nothing in-tree -- not client-core, not the widget
// kernel, not Compose. A dependency added here silently joins the floor, which
// is the one thing the boot config exists to keep small.
dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
