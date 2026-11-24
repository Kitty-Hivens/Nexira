plugins {
    id("nexira.look")
}

// The cursor axis of the look: the pointer over what a widget draws, asked by what it does there,
// pressed, refused, dragged, read or pulled by an edge, answered by a provider. A provider may leave a
// role alone, and the widget then leaves the pointer alone. When no provider is chosen for the axis the
// pointers are Compose's own and the platform's resize arrows. It knows no provider and no design
// system, only the manager's API it registers through.
// Its version is nexira.lookCursorApi in gradle.properties.
look {
    api()
}

kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":look:manager-api"))
        api(libs.compose.ui)
        implementation(libs.compose.runtime)
    }
}
