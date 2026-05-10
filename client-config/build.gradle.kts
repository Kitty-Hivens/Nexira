plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.buildconfig)
}

// Set up class generation
buildConfig {
    // Specify the package where the class will be generated
    packageName("hivens.config")

    // Generate the VERSION field, taking it from project.version
    // project.version is already installed in the root script
    buildConfigField("String", "FORK_VERSION", "\"${project.version}\"")

    // And build time...
    buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
}
