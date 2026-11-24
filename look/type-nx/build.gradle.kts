plugins {
    id("nexira.look")
    // For the bundled faces, which are Compose resources.
    alias(libs.plugins.compose)
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
        implementation(libs.compose.components.resources)
    }
}

kotlin {
    android {
        // Compose resources travel as assets of the Android library, which are only packaged when the
        // library takes part in resource processing.
        androidResources {
            enable = true
        }
    }
    sourceSets {
        getByName("jvmTest") {
            dependencies {
                // The faces are read by Skia, which takes the runtime of the machine running the tests.
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "hivens.look.type.nx.generated.resources"
    generateResClass = always
}

kotlin.sourceSets.named("jvmTest") {
    dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
}
