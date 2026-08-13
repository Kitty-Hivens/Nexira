package hivens.ui.screens.library.content

import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtSource
import hivens.launcher.instance.ContentKind
import hivens.launcher.instance.InstalledContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules the Content tab renders rows by: who may toggle what, where that
 * toggle is written, and what the pack still owns. Pure, so the matrix is pinned
 * without a scanner, a manifest client or an instance on disk -- it used to live
 * inside a list item's lambda, where none of it could be checked.
 */
class ContentTabRulesTest {

    private fun content(
        name: String,
        kind: ContentKind = ContentKind.Mod,
        enabled: Boolean = true,
    ) = InstalledContent(
        kind        = kind,
        fileName    = name,
        displayName = name.substringBeforeLast('.'),
        version     = null,
        description = null,
        enabled     = enabled,
        iconBytes   = null,
        sizeBytes   = 0,
    )

    private fun entry(name: String, required: Boolean) = SmrtModEntry(
        filename = name,
        sha1     = "0".repeat(40),
        sizeBytes = 0,
        required = required,
        source   = SmrtSource.Unknown,
    )

    // -- one row --------------------------------------------------------------

    @Test
    fun `a detached instance owns its mods outright`() {
        val rules = contentRowRules(content("sodium.jar"), manifestEntry = null, isLocal = true, optionalEnabled = null)
        assertTrue(rules.showToggle)
        assertTrue(rules.canDelete)
        assertFalse(rules.optional, "with no pack entry the toggle is a rename on disk")
        assertTrue(rules.effectiveEnabled)
    }

    @Test
    fun `a required pack mod cannot be touched`() {
        val rules = contentRowRules(
            content("core.jar", enabled = false),
            manifestEntry   = entry("core.jar", required = true),
            isLocal         = false,
            optionalEnabled = null,
        )
        assertFalse(rules.showToggle, "you cannot disable what the pack mandates")
        assertFalse(rules.canDelete)
        assertTrue(rules.effectiveEnabled, "a required mod reads as on even while the file says otherwise")
    }

    @Test
    fun `an optional pack mod toggles through the pack, not the disk`() {
        val rules = contentRowRules(
            content("shaders.jar"),
            manifestEntry   = entry("shaders.jar", required = false),
            isLocal         = false,
            optionalEnabled = false,
        )
        assertTrue(rules.showToggle)
        assertTrue(rules.optional, "the write goes to optional content, which the list renders from")
        assertFalse(rules.canDelete, "the pack still owns the file")
        assertFalse(rules.effectiveEnabled, "the pack's state wins over the name on disk")
    }

    @Test
    fun `an optional mod with no pack state yet falls back to the file`() {
        // The relabel on disk lands asynchronously; until the pack state carries
        // an entry, what the scan saw is the honest answer.
        val rules = contentRowRules(
            content("extras.jar", enabled = false),
            manifestEntry   = entry("extras.jar", required = false),
            isLocal         = false,
            optionalEnabled = null,
        )
        assertFalse(rules.effectiveEnabled)
    }

    @Test
    fun `cosmetics stay user-managed on a tracked instance`() {
        for (kind in listOf(ContentKind.ResourcePack, ContentKind.ShaderPack)) {
            val rules = contentRowRules(content("pretty.zip", kind = kind), null, isLocal = false, optionalEnabled = null)
            assertTrue(rules.showToggle, "$kind is not part of the pack contract")
            assertTrue(rules.canDelete, "$kind is not part of the pack contract")
        }
    }

    @Test
    fun `a tracked mod the pack does not list is display-only`() {
        // A Modrinth or SC instance: nothing curates the row here, and the
        // instance is not the user's to edit until it is detached.
        val rules = contentRowRules(content("stray.jar"), manifestEntry = null, isLocal = false, optionalEnabled = null)
        assertFalse(rules.showToggle)
        assertFalse(rules.canDelete)
    }

    // -- the list -------------------------------------------------------------

    private val items = listOf(
        content("sodium.jar"),
        content("iris.jar"),
        content("faithful.zip", kind = ContentKind.ResourcePack),
        content("complementary.zip", kind = ContentKind.ShaderPack),
    )

    @Test
    fun `the filter chips narrow by kind`() {
        assertEquals(4, filterContent(items, "", ContentFilter.All).size)
        assertEquals(2, filterContent(items, "", ContentFilter.Mods).size)
        assertEquals(1, filterContent(items, "", ContentFilter.ResourcePacks).size)
        assertEquals(1, filterContent(items, "", ContentFilter.ShaderPacks).size)
    }

    @Test
    fun `search matches the shown name and the file name, either case`() {
        assertEquals(listOf("sodium.jar"), filterContent(items, "SODIUM", ContentFilter.All).map { it.fileName })
        assertEquals(listOf("faithful.zip"), filterContent(items, "faith", ContentFilter.All).map { it.fileName })
        assertEquals(listOf("iris.jar"), filterContent(items, ".jar", ContentFilter.Mods).map { it.fileName }.filter { it == "iris.jar" })
    }

    @Test
    fun `filter and search compose`() {
        assertEquals(emptyList(), filterContent(items, "sodium", ContentFilter.ShaderPacks))
    }

    @Test
    fun `the optional scope narrows to what the pack leaves up to the player`() {
        // The section says what kind of thing it is; this says whether the player
        // may turn it off. They are separate questions and compose.
        val optional = setOf("sodium.jar")

        assertEquals(
            listOf("sodium.jar"),
            filterContent(items, "", ContentFilter.All, ContentScope.OptionalOnly, optional).map { it.fileName },
        )
        assertEquals(
            listOf("sodium.jar"),
            filterContent(items, "", ContentFilter.Mods, ContentScope.OptionalOnly, optional).map { it.fileName },
        )
        assertEquals(
            emptyList(),
            filterContent(items, "", ContentFilter.ShaderPacks, ContentScope.OptionalOnly, optional),
            "a shader pack is never the pack's optional content",
        )
        assertEquals(
            emptyList(),
            filterContent(items, "iris", ContentFilter.All, ContentScope.OptionalOnly, optional),
            "the search still applies inside the scope",
        )
    }

    @Test
    fun `an empty optional set shows nothing under the optional scope`() {
        // A local pack curates nothing, and an offline manifest fetch comes back
        // with nothing to curate: an empty list is the honest answer, not the whole
        // folder.
        assertEquals(emptyList(), filterContent(items, "", ContentFilter.All, ContentScope.OptionalOnly, emptySet()))
    }

    // -- a selection ----------------------------------------------------------

    @Test
    fun `the pack's share of a selection is what blocks an action`() {
        val manifest = mapOf(
            "core.jar" to entry("core.jar", required = true),
            "shaders.jar" to entry("shaders.jar", required = false),
        )
        val picked = listOf(
            content("core.jar"),      // pack-owned, required
            content("shaders.jar"),   // pack-owned, but the user may flip it
            content("stray.jar"),     // tracked instance, unlisted: still not the user's
            content("faithful.zip", kind = ContentKind.ResourcePack),
        )
        assertEquals(2, lockedCount(picked, isLocal = false, manifestMods = manifest))
        assertEquals(0, lockedCount(picked, isLocal = true, manifestMods = manifest), "detaching hands everything back")
    }
}
