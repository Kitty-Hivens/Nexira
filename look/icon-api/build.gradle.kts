plugins {
    id("nexira.look")
}

// The icon axis of the look: an icon asked by its Material Symbols name, or carried by the component
// itself, answered as a drawing by a provider, which is an icon library. When no provider is chosen for
// the axis the few icons a control cannot do without are drawn and the rest are blank. The size, the
// colour, the filling and the weight are the component's. It knows no provider and no design system,
// only the manager's API it registers through.
// Its version is nexira.lookIconApi in gradle.properties.
look {
    api()
}

kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":look:manager-api"))
        api(libs.compose.ui)
        implementation(libs.compose.runtime)
        implementation(libs.compose.foundation)
    }
}
