package hivens.widget

import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.DefaultLayout
import kotlin.test.Test
import kotlin.test.assertEquals

// Pins the kernel-3 contract: every widget kind referenced from the
// bundled default layout must exist in the KSP-generated registry.
// If a refactor renames a widget but forgets the layout JSON (or
// vice versa), SlotRenderer would silently skip the missing kind in
// production -- this catches it at compile time of the test.
class WidgetRegistryConsistencyTest {

    @Test
    fun `every default-layout widget kind exists in the generated registry`() {
        val graph = DefaultLayout.load()
        val referenced = graph.surfaces.values
            .flatMap { it.slots.values }
            .flatMap { it.widgets }
            .map { it.kind }
            .toSet()
        val available = GeneratedWidgetRegistry.all().keys

        val missing = referenced - available
        assertEquals(
            emptySet(),
            missing,
            "default-layout.json references widget kinds that the registry does not know about: $missing",
        )
    }

    @Test
    fun `registry exposes the kernel-3 + editor-2 sample widget kinds`() {
        val expected = setOf(
            // kernel-3 surface widgets
            "home.classic.content",
            "home.new.welcome",
            "home.new.recent",
            "home.new.quicklaunch",
            "library.header",
            "library.body",
            "appshell.leftrail.navbuttons",
            "appshell.leftrail.consoletoggle",
            "appshell.leftrail.logout",
            "appshell.rightrail.authpanel",
            "appshell.rightrail.compactnews",
            // editor-2 sample widgets
            "home.new.clock",
            "home.new.spacer",
            "home.new.progress",
            "home.new.launchbutton",
        )
        val actual = GeneratedWidgetRegistry.all().keys.map { it.value }.toSet()
        assertEquals(expected, actual, "registry drift -- expected these 15 widgets")
    }

    @Test
    fun `non-removable widgets are exactly the navigation + auth widgets`() {
        val nonRemovable = GeneratedWidgetRegistry.all().values
            .filterNot { it.removable }
            .map { it.kind.value }
            .toSet()
        assertEquals(
            setOf("appshell.leftrail.navbuttons", "appshell.rightrail.authpanel"),
            nonRemovable,
            "only nav buttons + auth panel may opt out of removable",
        )
    }
}
