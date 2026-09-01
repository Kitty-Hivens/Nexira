package hivens.widget

import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.DefaultLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
            "home.new.hero",
            "library.header",
            "library.body",
            "appshell.rightrail.compactnews",
            // shell-as-surface region widgets
            "appshell.region.left",
            "appshell.region.center",
            "appshell.region.right",
            // custom title bar (top region + body wrapper + breadcrumb)
            "appshell.region.top",
            "appshell.region.body",
            "appshell.topbar.breadcrumb",
            // floating activity account over the content column
            "appshell.activity.pill",
            // editor-2 sample widgets
            "home.new.clock",
            "home.new.spacer",
            "home.new.progress",
            "home.new.launchbutton",
            // editor-3.7 music
            "home.new.music",
            // inline video player (URL prop, expand-to-full)
            "home.new.video",
            // unified configurable nav rail item
            "nav.entry",
            // Phase A.3 container sample
            "container.group",
            // tab container
            "container.tabs",
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
            "profile.nav",
            "profile.skin.section",
            "profile.account.section",
            "profile.signin",
            "server.details.title",
            "server.details.tagbar",
            "server.details.description",
            "server.details.banner",
            "theme.picker.grid",
            "theme.picker.preview",
            // persistent notification history
            "notifications.history",
            // per-instance persisted state widgets
            "notes.scratch",
            "checklist",
        )
        val actual = GeneratedWidgetRegistry.all().keys.map { it.value }.toSet()
        assertEquals(expected, actual, "registry drift -- expected exactly these widgets")
    }

    @Test
    fun `every injected service contract has a provider in the build`() {
        val descriptors = GeneratedWidgetRegistry.all().values
        val provided = descriptors.flatMapTo(HashSet()) { it.provides }
        val unmet = descriptors
            .associate { it.kind.value to it.injects.filterNot { contract -> contract in provided } }
            .filterValues { it.isNotEmpty() }

        assertEquals(
            emptyMap(),
            unmet,
            "these widgets read a service contract no widget provides, so the registry hands them " +
                "null on every frame -- indistinguishable from a widget that does nothing",
        )
    }

    @Test
    fun `the service annotations reach the registry at all`() {
        // The pair that exists today. If this ever goes empty the processor has
        // stopped reading the annotations, and the check above passes vacuously.
        val descriptors = GeneratedWidgetRegistry.all().values
        assertTrue(
            descriptors.any { it.provides.isNotEmpty() } && descriptors.any { it.injects.isNotEmpty() },
            "no widget declares a service contract -- either the annotations are gone or KSP is not reading them",
        )
    }

    @Test
    fun `non-removable widgets cover the bundled-rail safety set`() {
        val nonRemovable = GeneratedWidgetRegistry.all().values
            .filterNot { it.removable }
            .map { it.kind.value }
            .toSet()
        assertEquals(
            setOf(
                "profile.signin",                // sign-in form: a user must not strand themselves logged-out
                "appshell.region.left",          // shell regions: the frame must stay whole
                "appshell.region.center",
                "appshell.region.right",
                "appshell.region.top",           // title bar: hosts window controls + breadcrumb
                "appshell.region.body",          // carries the whole app body
            ),
            nonRemovable,
            "non-removable set keeps the shell frame and sign-in panel intact; nav items " +
                "are removable and restored via the editor's leftrail surface reset",
        )
    }

    @Test
    fun `widgets exposing props match the Phase 5 audited set`() {
        val propful = GeneratedWidgetRegistry.all().values
            .filter { it.propsSerializer != null }
            .map { it.kind.value }
            .toSet()
        val expected = setOf(
            // display widgets
            "home.new.clock",
            "home.new.spacer",
            "home.new.progress",
            "home.new.welcome",
            "home.new.launchbutton",
            "home.new.music",
            "home.new.video",
            "home.new.recent",
            "home.new.quicklaunch",
            "home.new.hero",
            // About surface (title overrides)
            "about.logo",
            "about.system.card",
            "about.credits",
            "about.links.card",
            "about.update.panel",
            // Library
            "library.header",
            "library.body",
            // right-rail compact news (show-title prop)
            "appshell.rightrail.compactnews",
            // expressive knobs on otherwise data-driven sections
            "server.details.banner",
            "profile.skin.section",
            // tab container (tabCount + labels)
            "container.tabs",
            // shell regions (width / opacity / blur / divider / collapse frame props)
            "appshell.region.left",
            "appshell.region.center",
            "appshell.region.right",
            // title bar (height / corner / group / opacity / blur / controls props)
            "appshell.region.top",
            // unified nav rail item (target prop)
            "nav.entry",
            // persistent notification history (expand-direction + clock props)
            "notifications.history",
            // per-instance state widgets (props alongside their runtime state)
            "notes.scratch",
            "checklist",
            // floating activity account (measure / anchor / height props)
            "appshell.activity.pill",
        )
        assertEquals(
            expected,
            propful,
            "prop-exposing widget set drifted -- a @Widget(propsClass=...) was added or dropped",
        )
    }
}
