plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // @Composable annotation + Compose runtime types referenced by
    // WidgetDescriptor.Render. compose-runtime is the smallest dep that
    // brings in the annotation; the full compose plugin is overkill for
    // a contracts module.
    implementation(libs.compose.runtime)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
