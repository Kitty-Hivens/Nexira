plugins {
    id("nexira.look")
}

// The type axis of the look: the faces text is drawn in, asked by what the text is, the code face, and
// the face for a script the others lack, answered by a provider, and Compose's own families when no
// provider is chosen for the axis. Sizes and line heights are the component's and never asked here. It
// knows no provider and no design system, only the manager's API it registers through.
// Its version is nexira.lookTypeApi in gradle.properties.
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
