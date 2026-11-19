plugins {
    id("nexira.look")
}

// The shape axis of the look: the corners and outlines a widget asks by role, the answers a provider
// fills in, and the plain answers that hold where no provider answers. It knows no provider and no
// design system, only the manager's API it registers through.
// Its version is nexira.lookShapeApi in gradle.properties.
look {
    api()
}

kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":look:manager-api"))
        api(libs.compose.foundation)
        api(libs.compose.ui)
        implementation(libs.compose.runtime)
    }
}
