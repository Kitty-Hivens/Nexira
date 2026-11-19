plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose.compiler)
}

// A worked example of a module that brings two themes and two particle fields and no widgets, and not part of the
// launcher. It depends on the widget kernel, on Compose, and on the themes module's API, which a theme is brought
// through. The jar this produces is discovered from the widgets directory at boot, like any other module.
dependencies {
    api(project(":widget-model"))
    api(project(":widget-api"))
    implementation(project(":look:colour-nx-api"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)

    testImplementation(kotlin("test"))
    // The packaging test opens this module's own jar the way the launcher does.
    testImplementation(project(":widget-loader"))
}

tasks.test {
    useJUnitPlatform()
    // The packaging test reads this module's own jar, so it has to exist first.
    dependsOn(tasks.jar)
    systemProperty("nexira.test.moduleJar", tasks.jar.flatMap { it.archiveFile }.get().asFile.absolutePath)
}

tasks.jar {
    manifest {
        attributes(
            "Nexira-Widget-Api" to providers.gradleProperty("nexira.widgetApi").get(),
            "Nexira-Module-Id" to "looks",
            "Nexira-Module-Name" to "Looks",
            // The themes API its themes are brought through, which the loader checks the
            // launcher's against and without which the jar cannot see it.
            "Nexira-Module-Requires" to "nexira.themes=" + providers.gradleProperty("nexira.themesApi").get(),
        )
    }
}

// Drops the built jar where a running launcher will find it, which is the whole install procedure.
// Override the destination with -PwidgetsDir=... on platforms whose data directory is not the XDG one.
tasks.register<Copy>("installWidget") {
    group = "nexira"
    description = "Copy this module into the launcher's widgets directory"
    from(tasks.jar)
    into(
        providers.gradleProperty("widgetsDir").orElse(
            providers.systemProperty("user.home").map { "$it/.local/share/nexira/widgets" },
        ),
    )
}
