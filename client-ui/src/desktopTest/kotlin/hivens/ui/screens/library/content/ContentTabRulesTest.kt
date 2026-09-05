package hivens.ui.screens.library.content

import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtSource
import hivens.launcher.instance.ContentKind
import hivens.launcher.instance.InstalledContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
        val rules = contentRowRules(content("sodium.jar"), manifestEntry = null, userOwned = true, optionalEnabled = null)
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
            userOwned       = false,
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
            userOwned       = false,
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
            userOwned       = false,
            optionalEnabled = null,
        )
        assertFalse(rules.effectiveEnabled)
    }

    @Test
    fun `cosmetics stay user-managed on a tracked instance`() {
        for (kind in listOf(ContentKind.ResourcePack, ContentKind.ShaderPack)) {
            val rules = contentRowRules(content("pretty.zip", kind = kind), null, userOwned = false, optionalEnabled = null)
            assertTrue(rules.showToggle, "$kind is not part of the pack contract")
            assertTrue(rules.canDelete, "$kind is not part of the pack contract")
        }
    }

    @Test
    fun `a tracked mod the pack does not list is display-only`() {
        // A Modrinth or SC instance: nothing curates the row here, and the
        // instance is not the user's to edit until it is detached.
        val rules = contentRowRules(content("stray.jar"), manifestEntry = null, userOwned = false, optionalEnabled = null)
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
    fun `the optional axis narrows to what the pack leaves up to the player`() {
        // The section says what kind of thing a row is; this says whether the
        // player may turn it off. Separate questions, and they compose.
        val optional = setOf("sodium.jar")
        val only = ContentFilters(optionalOnly = true)

        assertEquals(
            listOf("sodium.jar"),
            filterContent(items, "", ContentFilter.All, only, optional).map { it.fileName },
        )
        assertEquals(
            emptyList(),
            filterContent(items, "", ContentFilter.ShaderPacks, only, optional),
            "a shader pack is never the pack's optional content",
        )
        assertEquals(
            emptyList(),
            filterContent(items, "iris", ContentFilter.All, only, optional),
            "the search still applies inside the axis",
        )
        assertEquals(
            emptyList(),
            filterContent(items, "", ContentFilter.All, only, emptySet()),
            "no manifest means nothing is curated, which is empty rather than everything",
        )
    }

    @Test
    fun `the state axis reads the row, not the file name`() {
        // An optional mod switched off is off in the record before the relabel
        // lands on disk, and the list has to agree with the switch beside it.
        val offByRecord = setOf("sodium.jar")
        val shown = filterContent(
            items       = items,
            query       = "",
            filter      = ContentFilter.All,
            filters     = ContentFilters(status = ContentStatus.Disabled),
            effectiveOn = { it.fileName !in offByRecord },
        )

        assertEquals(listOf("sodium.jar"), shown.map { it.fileName })
    }

    @Test
    fun `the owner axis separates what the pack ships from what the player added`() {
        // A resource pack the pack ships is in the manifest's ASSETS, under a path
        // rather than a bare name -- reading only the mods filed every mirror-shipped
        // resource pack under the player.
        val packContent = setOf(
            contentKey(ContentKind.Mod, "sodium.jar"),
            contentKey(ContentKind.ResourcePack, "faithful.zip"),
        )

        assertEquals(
            listOf("sodium.jar", "faithful.zip"),
            filterContent(items, "", ContentFilter.All, ContentFilters(owner = ContentOwner.Pack), packKeys = packContent)
                .map { it.fileName },
        )
        assertEquals(
            listOf("iris.jar", "complementary.zip"),
            filterContent(items, "", ContentFilter.All, ContentFilters(owner = ContentOwner.User), packKeys = packContent)
                .map { it.fileName },
        )
    }

    @Test
    fun `the same file name in two folders is two different rows`() {
        // The pack ships a mod called pack.jar; the player drops a resource pack
        // called pack.jar. Matching on the name alone would hand the player's file
        // to the pack.
        val both = listOf(
            content("pack.jar", ContentKind.Mod),
            content("pack.jar", ContentKind.ResourcePack),
        )
        val packContent = setOf(contentKey(ContentKind.Mod, "pack.jar"))

        assertEquals(
            listOf(ContentKind.Mod),
            filterContent(both, "", ContentFilter.All, ContentFilters(owner = ContentOwner.Pack), packKeys = packContent)
                .map { it.kind },
        )
        assertEquals(
            listOf(ContentKind.ResourcePack),
            filterContent(both, "", ContentFilter.All, ContentFilters(owner = ContentOwner.User), packKeys = packContent)
                .map { it.kind },
        )
    }

    @Test
    fun `the pack's recorded files become row keys, and the rest is dropped`() {
        val keys = placedKeysFrom(
            setOf(
                "mods/sodium.jar",
                "resourcepacks/FreshAnimations.zip",
                "shaderpacks/complementary.zip",
                "config/sodium-options.json",
                "options.txt",
            ),
        )

        assertEquals(
            setOf(
                contentKey(ContentKind.Mod, "sodium.jar"),
                contentKey(ContentKind.ResourcePack, "FreshAnimations.zip"),
                contentKey(ContentKind.ShaderPack, "complementary.zip"),
            ),
            keys,
            "only what this tab has a row for",
        )
    }

    @Test
    fun `no record stays unknown all the way through`() {
        assertNull(placedKeysFrom(null), "an empty set here would read as 'the pack owns nothing'")
        assertEquals(emptySet(), placedKeysFrom(emptySet()))
    }

    @Test
    fun `a manifest asset is filed under the folder its path names`() {
        assertEquals(ContentKind.ResourcePack, kindOfDest("resourcepacks/FreshAnimations.zip"))
        assertEquals(ContentKind.ShaderPack, kindOfDest("shaderpacks/complementary.zip"))
        assertEquals(ContentKind.Mod, kindOfDest("mods/sodium.jar"))
        assertNull(kindOfDest("config/sodium-options.json"), "a config has no row here to classify")
        assertNull(kindOfDest("options.txt"))
    }

    @Test
    fun `the axes compose, and the count of active ones is what the trigger badges`() {
        val filters = ContentFilters(optionalOnly = true, status = ContentStatus.Enabled, owner = ContentOwner.Pack)
        assertEquals(3, filters.activeCount)
        assertEquals(0, ContentFilters().activeCount)
        assertTrue(ContentFilters().isEmpty)

        val shown = filterContent(
            items         = items,
            query         = "",
            filter        = ContentFilter.Mods,
            filters       = filters,
            optionalNames = setOf("sodium.jar", "iris.jar"),
            packKeys      = setOf(contentKey(ContentKind.Mod, "sodium.jar")),
            effectiveOn   = { true },
        )
        assertEquals(listOf("sodium.jar"), shown.map { it.fileName }, "optional AND on AND the pack's AND a mod")
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
        assertEquals(2, lockedCount(picked, userOwns = { false }, manifestMods = manifest))
        assertEquals(0, lockedCount(picked, userOwns = { true }, manifestMods = manifest), "detaching hands everything back")
    }
}
