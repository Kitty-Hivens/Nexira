plugins {
    id("nexira.look")
    // For the tests only: a plane is held to the pixels it draws, off screen, which takes the Skia
    // runtime of the machine running them.
    alias(libs.plugins.compose)
}

// The surface axis of the look: the planes a widget asks by kind, what each is made of (its depth on
// the ladder, its body, opacity, blur and edge), the plate a widget's own plane takes where its node
// leaves a field empty, and the flat planes that hold where no provider answers. It knows no provider
// and no design system, only the manager's API it registers through.
// Its version is nexira.lookSurfaceApi in gradle.properties.
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

kotlin {
    sourceSets {
        getByName("jvmTest") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.compose.foundation)
            }
        }
    }
}
