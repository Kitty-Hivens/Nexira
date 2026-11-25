plugins {
    id("nexira.look")
}

// What draws previews and tests of a component library that answers no axis itself, as an interface
// found on the class path. A module of its own so that what implements it, :look:preview, and what asks for
// it, nx-ui, know neither each other nor the same third module: nx-ui has the implementation on the
// class path of its previews, and one built on nx-ui would close a circle there.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        implementation(libs.compose.runtime)
    }
}
