plugins {
    id("nexira.look")
}

// The system's own pointers as a provider of the cursor axis: what the system has for a role, and
// nothing where it has none. On Linux the cursor theme's, asked by name, on Windows and macOS the ones
// the toolkit knows, on Android the pointer types. It brings no picture of its own. It has no settings
// and no keys. Compiled into the application, and brought in by the look manager when the person's choice
// names system for cursor, or a provider that stands on it.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":widget-api"))
        implementation(project(":look:cursor-api"))
        implementation(libs.compose.runtime)
        implementation(libs.compose.ui)
    }
}

kotlin.sourceSets.named("jvmMain") {
    dependencies {
        implementation(libs.slf4j.api)
    }
}
