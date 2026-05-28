plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose.compiler)
    // widget-api is serialization-aware: WidgetDescriptor exposes a
    // KSerializer, WidgetProps decodes instance props, and prop classes
    // declared here (or in tests) need generated serializers.
    alias(libs.plugins.kotlin.serialization)
}

// Compose-bound surface for the widget kernel. Data shapes (ids,
// layout graph, default-layout resource, @Widget annotation) live in
// :widget-model so non-UI surfaces -- launcher persistence today,
// future CLI / TUI -- can consume them without dragging compose-runtime.
dependencies {
    api(project(":widget-model"))
    implementation(libs.compose.runtime)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
