plugins {
    id("nexira.look")
    // For the tests only: a stroke is held to the pixels Compose's own border and line draw, off
    // screen, which takes the Skia runtime of the machine running them.
    alias(libs.plugins.compose)
}

// The stroke axis of the look: the edges, marks, dividers and placeholder outlines a widget asks by
// role, the answers a provider fills in, which draw themselves, and the hairlines that hold where no
// provider answers. It knows no provider and no design system, only the manager's API it registers
// through.
// Its version is nexira.lookStrokeApi in gradle.properties.
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

kotlin {
    sourceSets {
        getByName("jvmTest") {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
