plugins {
    id("nexira.look")
}

// The timing axis of the look: how long a transient piece of the interface waits before it appears,
// asked by what it is, the answers a provider fills in, and Compose's own waits where no provider
// answers. It knows no provider and no design system, only the manager's API it registers through.
// Its version is nexira.lookTimingApi in gradle.properties.
look {
    api()
}

kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":look:manager-api"))
        implementation(libs.compose.runtime)
    }
}
