package hivens.packs.api

import hivens.widget.model.ApiVersion
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PacksApiTest {

    @Test
    fun `the version module builds stamp is the version the API says it is`() {
        val stamped = requireNotNull(System.getProperty("nexira.packsApi")) { "the build did not pass nexira.packsApi" }
        assertEquals(PacksApi.VERSION, ApiVersion.parse(stamped))
    }

    @Test
    fun `every typed key carries the id its constant names, at the app level`() {
        val pairs = listOf<Pair<String, Any>>(
            PackKeys.LIBRARY_ID to PackKeys.Library,
            PackKeys.LAUNCHES_ID to PackKeys.Launches,
            PackKeys.LAUNCH_ID to PackKeys.Launch,
            PackKeys.INSTALLS_ID to PackKeys.Installs,
            PackKeys.UPDATES_ID to PackKeys.Updates,
            PackKeys.OPERATIONS_ID to PackKeys.Operations,
            PackKeys.REATTACH_PREVIEWS_ID to PackKeys.ReattachPreviews,
            PackKeys.LAUNCH_PACK_ID to PackKeys.LaunchPack,
            PackKeys.STOP_PACK_ID to PackKeys.StopPack,
            PackKeys.INSTALL_PACK_ID to PackKeys.InstallPack,
            PackKeys.DISMISS_INSTALL_ID to PackKeys.DismissInstall,
            PackKeys.CANCEL_INSTALL_ID to PackKeys.CancelInstall,
            PackKeys.UPDATE_PACK_ID to PackKeys.UpdatePack,
            PackKeys.DELETE_PACK_ID to PackKeys.DeletePack,
            PackKeys.PREVIEW_REATTACH_ID to PackKeys.PreviewReattach,
            PackKeys.FORGET_REATTACH_PREVIEW_ID to PackKeys.ForgetReattachPreview,
            PackKeys.REATTACH_PACK_ID to PackKeys.ReattachPack,
        )
        pairs.forEach { (id, key) ->
            val (keyId, level) = when (key) {
                is SourceKey<*> -> key.id to key.level
                is CommandKey<*> -> key.id to key.level
                else -> error("not a key: $key")
            }
            assertEquals(id, keyId)
            assertEquals(ScopeLevel.App, level, id)
        }
        assertEquals(pairs.size, pairs.map { it.first }.toSet().size, "two keys share an id")
    }

    @Test
    fun `the ids the launcher shipped before the module existed are kept`() {
        // The built-in widgets name these in their needs, and the layout files and the
        // keys written by hand in a module refer to them by id, so a rename would leave
        // those widgets misplaced with nothing failing at build time.
        val shipped = setOf(
            "packs.library", "packs.launches", "launch.state", "packs.installs", "packs.reattach.previews",
            "pack.launch", "pack.stop", "pack.install", "pack.install.dismiss", "pack.install.cancel",
            "pack.update", "pack.delete", "pack.reattach.preview", "pack.reattach",
        )
        val now = setOf(
            PackKeys.LIBRARY_ID, PackKeys.LAUNCHES_ID, PackKeys.LAUNCH_ID, PackKeys.INSTALLS_ID, PackKeys.REATTACH_PREVIEWS_ID,
            PackKeys.LAUNCH_PACK_ID, PackKeys.STOP_PACK_ID, PackKeys.INSTALL_PACK_ID, PackKeys.DISMISS_INSTALL_ID,
            PackKeys.CANCEL_INSTALL_ID, PackKeys.UPDATE_PACK_ID, PackKeys.DELETE_PACK_ID, PackKeys.PREVIEW_REATTACH_ID,
            PackKeys.REATTACH_PACK_ID,
        )
        assertTrue(now.containsAll(shipped), "missing: ${shipped - now}")
    }
}
