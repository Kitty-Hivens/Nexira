import hivens.android.launcherApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// Leaf design-system module (NxUI). Holds tokens, primitives, surfaces and the
// Flexible decorator layer that the rest of the UI builds on. Depends in-tree on
// ui-layers, which draws nothing, and on the colour axis's contract, whose answers
// its colours are read from. client-ui depends on it one-way.
// No application/packaging here, no puppet source-set, no client-core: this is
// the bottom of the UI graph.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

group = "hivens"

// Repositories live in settings.gradle.kts (FAIL_ON_PROJECT_REPOS); no block here.

kotlin {
    // KMP modules don't apply the `java` plugin, so the root's toolchain pin
    // (java-plugin modules only) skips them; set it here so org.gradle.jvm.version
    // matches the JDK 27 leaf modules instead of falling back to the daemon JVM.
    jvmToolchain(27)
    jvm("desktop")
    android {
        namespace = "hivens.nx.ui"
        compileSdk { launcherApi(providers) }
        minSdk = 31
        // Compose resources travel as assets of the Android library, which are only packaged when the library takes part in resource processing.
        androidResources {
            enable = true
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    sourceSets {
        val jvmSharedMain = create("jvmSharedMain") {
            dependsOn(getByName("commonMain"))
        }
        getByName("desktopMain").dependsOn(jvmSharedMain)
        getByName("androidMain").dependsOn(jvmSharedMain)

        getByName("jvmSharedMain") {
            dependencies {
                // Where popups, tooltips and dialogs stand and what closes them. This
                // module draws them and asks ui-layers to place them.
                implementation(project(":ui-layers"))
                // What every colour here is read from: the colour axis's answers, provided by
                // whatever answers the axis around the composition, and its base answers outside any.
                api(project(":look:colour-api"))
                // What every movement here is read from: the motion axis's answers, the same way.
                api(project(":look:motion-api"))
                // What every corner here is read from: the shape axis's answers, the same way.
                api(project(":look:shape-api"))
            }
        }

        getByName("commonMain") {
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.ui)
                implementation(libs.compose.components.resources)
                // The @Preview annotation the IDE draws components from. Annotation only.
                implementation(libs.compose.ui.tooling.preview)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.material.color.utilities)
                implementation(libs.androidx.graphics.shapes)
                implementation(libs.slf4j.api)
            }
        }

        getByName("desktopTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                // Skiko native runtime (current OS) so off-screen render tests
                // (ImageComposeScene) can create a Skia surface. Test-only -- the
                // published leaf module stays free of any compose.desktop dep.
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// Own composeResources -- the icon + typography fonts live here. The generated
// Res accessor uses a DIFFERENT package than client-ui's (hivens.ui.generated.resources)
// so the two Res objects never collide on the classpath.
// Every class this artifact holds, one binary name per line, for the widget module loader:
// widget modules are shown the design system class by class from this list, because its
// packages (hivens.ui.theme, hivens.ui.nx) also hold classes of the launcher's own
// interface, which modules are not shown. The loader reads the list off the application at
// run time, so it holds in the merged release jar as it does on a class path of separate
// jars. See SharedClassList in :widget-loader for the format and the path.
val sharedClassListDir = layout.buildDirectory.dir("generated/sharedClassList")
val listSharedClasses = tasks.register("listSharedClasses") {
    // The compile task's own output and not the compilation's classes, which the
    // resources this list goes into are part of.
    val classDirs = files(tasks.named<KotlinJvmCompile>("compileKotlinDesktop").flatMap { it.destinationDirectory })
    val listFile = sharedClassListDir.map { it.file("META-INF/nexira/shared/nx-ui.classes") }
    inputs.files(classDirs).withPropertyName("classes").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(listFile).withPropertyName("list")
    doLast {
        val names = classDirs.files.filter { it.isDirectory }.flatMap { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") && it.name != "module-info.class" }
                .map { it.relativeTo(dir).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.') }
                .toList()
        }.sorted()
        listFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(names.joinToString("\n", postfix = "\n"))
        }
    }
}
kotlin.sourceSets.getByName("desktopMain").resources.srcDir(files(sharedClassListDir).builtBy(listSharedClasses))

// What the IDE draws a @Preview with on the Android side. On this module's own runtime class
// path only, so it reaches neither the published artifact nor the app.
dependencies {
    "androidRuntimeClasspath"(libs.compose.ui.tooling)
}

compose.resources {
    publicResClass = true
    packageOfResClass = "hivens.nx.ui.generated.resources"
    generateResClass = always
}

// jvmTarget is also forced project-wide by the root subprojects {} block; set it
// explicitly here too, matching client-ui, so the module is correct in isolation.
tasks.withType<KotlinJvmCompile>().matching { !it.name.contains("Android") }.configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_27)
    }
}
