plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Pure data module. No Compose, no UI dep, no @Composable. Consumers
// that only want the data shape (launcher persistence, future CLI /
// TUI surfaces that never touch Compose) depend on this module;
// Compose-tainted bits live in :widget-api one layer up.
dependencies {
    // api (not implementation): WidgetInstance.props is a JsonObject and
    // WidgetDescriptor in :widget-api now exposes KSerializer +
    // JsonObject for typed props, so the serialization types must be on
    // the compile classpath of modules that depend on :widget-model.
    api(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
