// The root build's version catalog, so the plugins buildSrc puts on every build script's classpath
// are pinned where every other version is, and a bump there moves them too.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
