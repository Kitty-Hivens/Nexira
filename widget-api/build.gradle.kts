plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose.compiler)
}

// Compose-bound surface for the widget kernel. Data shapes (ids,
// layout graph, default-layout resource, @Widget annotation) live in
// :widget-model so non-UI surfaces -- launcher persistence today,
// future CLI / TUI -- can consume them without dragging compose-runtime.
dependencies {
    api(project(":widget-model"))
    implementation(libs.compose.runtime)
}
