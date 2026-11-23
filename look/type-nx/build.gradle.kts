plugins {
    id("nexira.look")
}

// The nx type provider: it answers the type axis of :look:type-api with nx's faces, which nx-ui
// keeps (nxTypeAnswers) because its previews draw with them too. It has no settings and no keys, so
// it answers the same before the host is up and after. Compiled into the application, and brought in
// by the look manager when the person's choice names nx for type.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":widget-api"))
        implementation(project(":look:type-api"))
        implementation(project(":nx-ui"))
        implementation(libs.compose.runtime)
    }
}

kotlin.sourceSets.named("jvmTest") {
    dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
}
