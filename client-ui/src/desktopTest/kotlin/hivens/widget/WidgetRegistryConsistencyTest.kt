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
    fun `registry exposes the kernel-3 + editor sample widget kinds`() {
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
            // editor-3.7 music + individual nav
            "home.new.music",
            "nav.home",
            "nav.library",
            "nav.browse",
            "nav.profile",
            "nav.settings",
            "nav.about",
            // Phase A.3 container sample
            "container.group",
            // Phase D service-consumer sample
            "home.new.playback.mini",
            // Phase B.1 widgetized screens (incremental landing)
            "about.logo",
            "about.credits",
            "about.update.panel",
            "about.system.card",
            "about.links.card",
            "bg.enable.toggle",
            "bg.image.picker",
            "bg.scale.mode",
            "bg.position.x",
            "bg.position.y",
            "bg.fx.blur",
            "bg.fx.darken",
            "bg.fx.opacity",
            "bg.fx.saturation",
            "bg.fx.parallax",
            "bg.fx.vignette",
            "bg.fx.animspeed",
            "bg.loop.mode",
            "bg.tint",
            "bg.reset",
            "bg.preview",
            "profile.nav",
            "profile.skin.section",
            "profile.account.section",
            "server.details.title",
            "server.details.tagbar",
            "server.details.description",
            "server.details.banner",
            "theme.picker.grid",
            "theme.picker.preview",
        )
        val actual = GeneratedWidgetRegistry.all().keys.map { it.value }.toSet()
        assertEquals(expected, actual, "registry drift -- expected exactly these widgets")
    }

    @Test
    fun `non-removable widgets cover the bundled-rail safety set`() {
        val nonRemovable = GeneratedWidgetRegistry.all().values
            .filterNot { it.removable }
            .map { it.kind.value }
            .toSet()
        assertEquals(
            setOf(
                "appshell.leftrail.navbuttons", // bundled rail variant
                "appshell.rightrail.authpanel",
                "nav.settings",                  // individual-nav safety: always reachable
            ),
            nonRemovable,
            "non-removable set protects the launcher from being navigation-locked",
        )
    }
}
