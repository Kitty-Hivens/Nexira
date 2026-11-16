plugins {
    id("nexira.look")
    alias(libs.plugins.kotlin.serialization)
}

// What a module compiles against to bring an axis of the look, a provider for one, or a pack
// naming providers, and what the host reads the look through: one key with the answers of every
// axis. The module that answers it is :look:manager.
// Its version is nexira.lookManagerApi in gradle.properties.
look {
    api()
}

kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":widget-api"))
        implementation(libs.compose.runtime)
    }
}
