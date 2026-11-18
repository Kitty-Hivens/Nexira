plugins {
    id("nexira.look")
    // For the tests only: the widget is drawn off screen, which takes the Skia runtime of the machine
    // running them.
    alias(libs.plugins.compose)
    alias(libs.plugins.ksp)
}

// The UI that steers the nx motion provider: the reduce motion switch. A widget, which reads and fires
// the provider's own keys, and one source of its own, read off the look's state: whether nx draws the
// motion, which the widget is shown under, so a slot leaves it out where nx does not draw. Compiled into
// the application, which draws the widget by kind and names nothing of this module but its
// CompiledModule. Nothing compiles against it, so it has no API. It compiles against the provider's
// module for the provider's keys, as a provider's own controls may.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        implementation(project(":look:motion-nx"))
        implementation(project(":look:motion-api"))
        implementation(project(":look:manager-api"))
        implementation(project(":widget-api"))
        implementation(project(":widget-model"))
        implementation(project(":nx-ui"))
        implementation(project(":i18n-api"))
        implementation(project(":ui-puppet"))
        implementation(libs.compose.runtime)
        implementation(libs.compose.foundation)
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

// The widgets' registry, one per target, under a name of this module's own: two modules emitting
// the processor's default object would collide on the classpath and one would lose its widgets.
dependencies {
    add("kspJvm", project(":widget-processor"))
    add("kspAndroid", project(":widget-processor"))
}

ksp {
    arg("widgetRegistryPackage", "hivens.look.motion.nx.controls.generated")
    arg("widgetRegistryName", "MotionControlsWidgetRegistry")
}
