plugins {
    id("nexira.look")
}

// The packs that ship: the default pack, nexira, naming a provider family for each axis that exists.
// A pack is data and names providers by id, so this knows the manager's API and no axis or provider.
// Compiled into the application, and read by the manager whether a document names it or not.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":look:manager-api"))
    }
}

kotlin.sourceSets.named("jvmTest") {
    dependencies {
        implementation(project(":look:manager"))
        implementation(project(":look:colour-api"))
        implementation(project(":look:motion-api"))
        implementation(project(":look:shape-api"))
        implementation(project(":look:stroke-api"))
        implementation(libs.compose.runtime)
    }
}
