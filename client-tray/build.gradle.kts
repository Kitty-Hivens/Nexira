plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // The tray's only real dependency: libtray renders the native icon + menu
    // (DBusMenu / Shell_NotifyIcon / NSMenu). No client-core, no launch engine,
    // no auth -- the tray is a window-and-status surface, so the menu can never
    // reach the launch pipeline. That boundary is the whole point of the seam.
    implementation(libs.libtray)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
