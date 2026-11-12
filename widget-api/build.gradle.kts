import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
    // widget-api is serialization-aware: WidgetDescriptor exposes a
    // KSerializer, WidgetProps decodes instance props, and prop classes
    // declared here (or in tests) need generated serializers.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

// Compose-bound surface for the widget kernel. Data shapes (ids,
// layout graph, default-layout resource, @Widget annotation) live in
// :widget-model so non-UI surfaces -- launcher persistence today,
// future CLI / TUI -- can consume them without dragging compose-runtime.
kotlin {
    // KMP modules don't apply the `java` plugin, so the root's toolchain pin
    // skips them; set it here so org.gradle.jvm.version matches the JDK 27 modules.
    jvmToolchain(27)

    // A module jar is compiled against this API and is refused by version when it
    // changes, so every change to the public surface has to show: the dump in api/
    // is checked by `check`, and updateKotlinAbi rewrites it on purpose. Calling it
    // is what switches it on.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    jvm()
    android {
        namespace = "hivens.widget.api"
        compileSdk = providers.gradleProperty("nexira.android.compileSdk").map(String::toInt).getOrElse(37)
        minSdk = 31
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    sourceSets {
        val jvmSharedMain = create("jvmSharedMain") {
            dependsOn(getByName("commonMain"))
        }
        getByName("jvmMain").dependsOn(jvmSharedMain)
        getByName("androidMain").dependsOn(jvmSharedMain)

        getByName("jvmSharedMain") {
            dependencies {
                api(project(":widget-model"))
                implementation(libs.compose.runtime)
                // Color is in the signature of what a theme and a particle field declare.
                api(libs.compose.ui)
                // Phase G: SlotRenderer owns the slot's intra-slot layout, so it
                // needs the layout primitives (Column/Row/Box/Modifier). foundation
                // pulls compose-ui (Modifier) transitively.
                implementation(libs.compose.foundation)
                // api: a level hands its services to factories and owners as a Koin scope,
                // so the scope type is on the public contract of WidgetScope.
                api(libs.koin.core)
                // A level that fails to make or close a source logs it and goes on,
                // since a throw while a frame applies takes the window down.
                implementation(libs.slf4j.api)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                // runTest / TestScope for the suspendCommand fire-and-forget test.
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // What the module builds stamp into their manifests, for the test that holds it
    // equal to WidgetApi.VERSION.
    systemProperty("nexira.widgetApi", providers.gradleProperty("nexira.widgetApi").get())
}
