plugins {
    id("nexira.look")
}

// The look manager, compiled into the application and brought in by a document that names it.
// It knows no axis: the axes, their providers and the packs arrive as contributions, and it
// answers :look:manager-api's keys with the providers the person's choice names.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":look:manager-api"))
        api(project(":widget-api"))
        implementation(libs.compose.runtime)
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.slf4j.api)
    }
}

kotlin.sourceSets.named("jvmTest") {
    dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
}
