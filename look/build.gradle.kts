// The look modules, each a project under :look built by the nexira.look convention plugin
// in buildSrc: the manager, and the axes, providers and packs that come after it. This
// project holds no code of its own, only the task the test workflow names for all of their
// suites, so a module included under :look is in CI without a line of its own there.
// A look module's name repeats one at the root, :look:packs beside :packs, and Gradle tells projects
// apart in a dependency graph by group and name, so under the root's one group the two would be taken
// for one module and only one of them would reach a classpath.
subprojects {
    group = "hivens.look"
}

tasks.register("allTests") {
    group = "verification"
    description = "Runs the tests of every look module."
    dependsOn(subprojects.map { "${it.path}:test" })
}
