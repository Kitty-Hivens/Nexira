plugins {
    id("nexira.look")
    // For the tests only: the widgets are drawn off screen, which takes the Skia runtime of the
    // machine running them.
    alias(libs.plugins.compose)
    alias(libs.plugins.ksp)
}

// The UI that steers the nx colour provider: the day and night switch, what decides dark and light,
// the theme picker and its previews. Widgets, which fire the provider's own commands and read its
// own sources, and one source of its own, read off the look's state: whether nx draws the colour,
// which every widget is shown under, so a slot leaves it out where nx does not draw. Compiled into
// the application, which draws the widgets by kind and names nothing of this module but its
// CompiledModule. Nothing compiles against it, so it has no API.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        implementation(project(":look:colour-nx-api"))
        implementation(project(":look:colour-api"))
        implementation(project(":look:shape-api"))
        implementation(project(":look:manager-api"))
        implementation(project(":widget-api"))
        implementation(project(":widget-model"))
        implementation(project(":nx-ui"))
        implementation(project(":i18n-api"))
        implementation(project(":ui-puppet"))
        implementation(libs.compose.runtime)
        implementation(libs.compose.foundation)
        implementation(libs.compose.material3)
        implementation(libs.kotlinx.coroutines.core)
    }
}

kotlin {
    sourceSets {
        getByName("jvmTest") {
            dependencies {
                implementation(project(":look:colour-nx"))
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// The widgets' registry, one per target, under a name of this module's own: two modules emitting
// the processor's default object would collide on the classpath and one would lose its widgets.
dependencies {
    add("kspJvm", project(":widget-processor"))
    add("kspAndroid", project(":widget-processor"))
}

ksp {
    arg("widgetRegistryPackage", "hivens.look.colour.nx.controls.generated")
    arg("widgetRegistryName", "ControlsWidgetRegistry")
}
