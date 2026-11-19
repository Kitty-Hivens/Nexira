plugins {
    id("nexira.look")
}

// The nx shape provider: it answers the shape axis of :look:shape-api with nx's corners, which
// nx-ui keeps (nxShapeAnswers) because its previews draw with them too. It has no settings and no
// keys, so it answers the same before the host is up and after. Compiled into the application, and
// brought in by the look manager when the person's choice names nx for shape.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":widget-api"))
        implementation(project(":look:shape-api"))
        implementation(project(":nx-ui"))
        implementation(libs.compose.runtime)
    }
}

kotlin.sourceSets.named("jvmTest") {
    dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
}
