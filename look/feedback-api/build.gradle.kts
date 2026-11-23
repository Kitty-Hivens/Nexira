plugins {
    id("nexira.look")
    // For the tests only: the default feedback is held to the pixels Compose draws, off screen, which
    // takes the Skia runtime of the machine running them.
    alias(libs.plugins.compose)
}

// The feedback axis of the look: how a widget answers being hovered, focused, pressed or dragged,
// asked by what the widget is, answered as a Compose Indication a provider fills in, and Compose's own
// indication where no provider is chosen. It knows no provider and no design system, only the
// manager's API it registers through.
// Its version is nexira.lookFeedbackApi in gradle.properties.
look {
    api()
}

kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":look:manager-api"))
        api(libs.compose.foundation)
        api(libs.compose.ui)
        implementation(libs.compose.runtime)
        implementation(libs.kotlinx.coroutines.core)
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
