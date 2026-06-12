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
    // Phase G: SlotRenderer owns the slot's intra-slot layout, so it
    // needs the layout primitives (Column/Row/Box/Modifier). foundation
    // pulls compose-ui (Modifier) transitively.
    implementation(libs.compose.foundation)

    testImplementation(kotlin("test"))
    // runTest / TestScope for the suspendCommand fire-and-forget test.
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
