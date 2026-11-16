// The look modules, each a project under :look built by the nexira.look convention plugin
// in buildSrc: the manager, and the axes, providers and packs that come after it. This
// project holds no code of its own, only the task the test workflow names for all of their
// suites, so a module included under :look is in CI without a line of its own there.
tasks.register("allTests") {
    group = "verification"
    description = "Runs the tests of every look module."
    dependsOn(subprojects.map { "${it.path}:test" })
}
