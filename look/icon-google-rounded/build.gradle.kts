plugins {
    id("nexira.look")
    // For the tests only: the icons are drawn off screen, which takes the Skia runtime of the machine
    // running them.
    alias(libs.plugins.compose)
}

// The Google icon library as an icon provider: Material Symbols in the Rounded style, every icon of it.
// It answers the icon axis of :look:icon-api and compiles against nothing else of the look, no design
// system among it. The font and the table of its names are written by tools/icons/regenerate.py. It has
// no settings and no keys, so it answers the same before the host is up and after. Compiled into the
// application, and brought in by the look manager when the person's choice names google-rounded for icon.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":widget-api"))
        implementation(project(":look:icon-api"))
        implementation(libs.compose.runtime)
        implementation(libs.compose.foundation)
        implementation(libs.compose.ui)
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
