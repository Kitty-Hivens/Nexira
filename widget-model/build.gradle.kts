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
    // api (not implementation): WidgetDataSource.state is a StateFlow on the
    // public contract, so downstream modules (widget-api, client-ui) need the
    // coroutines types on their compile classpath. Stays Compose-free.
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
