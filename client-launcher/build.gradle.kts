plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.20-RC3"
}

val ktorVersion: String by project
val koinVersion: String by project
val slf4jVersion: String by project
val commonsCompressVersion: String by project
val coroutinesVersion: String by project
val mockkVersion: String by project

dependencies {
    implementation(project(":client-core"))
    implementation(project(":client-config"))

    implementation("org.apache.commons:commons-compress:$commonsCompressVersion")
    implementation("io.insert-koin:koin-core:$koinVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")

    // Ktor Client & Serialization
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // ─── TEST ────────────────────────────────────────────────────────────────
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("io.mockk:mockk:$mockkVersion")
    testImplementation("org.slf4j:slf4j-simple:$slf4jVersion")

    // MockClientFactory and other test utilities from client-core
    testImplementation(testFixtures(project(":client-core")))
}

tasks.test {
    useJUnitPlatform()
}
