plugins {
    id("nexira.look")
}

// The motion axis of the look: what a widget asks of movement, by role and in neutral words, the
// answers a provider fills in, and the plain answers that hold where no provider answers. It knows no
// provider and no design system, only the manager's API it registers through, and the kernel's word
// for how a widget arrives, which it answers.
// Its version is nexira.lookMotionApi in gradle.properties.
look {
    api()
}

kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":look:manager-api"))
        api(project(":widget-model"))
        api(libs.compose.animation)
        api(libs.compose.ui)
        implementation(libs.compose.runtime)
    }
}
