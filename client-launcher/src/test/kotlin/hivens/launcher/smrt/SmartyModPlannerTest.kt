package hivens.launcher.smrt

import hivens.core.api.model.ServerProfile
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.core.data.SettingsData
import hivens.launcher.ManifestProcessorService
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmartyModPlannerTest {

    private val manifestProcessor = ManifestProcessorService(Json { ignoreUnknownKeys = true })
    private val helperJar = Path.of("/tmp/open-smrt-network-1.12.jar")

    private fun manifest() = FileManifest(
        directories = mapOf(
            "mods" to FileManifest(
                files = mapOf(
                    "Smarty-1.12.2.jar" to FileData(md5 = "a", size = 1),
                    "JEI.jar" to FileData(md5 = "b", size = 2),
                ),
            ),
        ),
    )

    private fun server() = ServerProfile(name = "Industrial", version = "1.12.2", assetDir = "Industrial")

    private fun plannerResolving(resolved: OpenSmrtHelperResolver.Resolved?) =
        SmartyModPlanner(resolveHelper = { resolved }, manifestProcessor = manifestProcessor)

    @Test
    fun `helper on and resolvable strips Smarty and injects helper`() = runTest {
        val resolved = OpenSmrtHelperResolver.Resolved(helperJar, listOf("Smarty*.jar"))
        val plan = plannerResolving(resolved).plan(
            server(), manifest(),
            SettingsData(useOpenSmrtHelper = true, strictModVerification = true),
        )

        assertEquals(setOf("Smarty-1.12.2.jar"), plan.ignoredAddon, "only the Smarty jar should be stripped")
        assertEquals(helperJar, plan.injectJar)
        assertTrue(plan.strict)
        assertEquals(listOf("open-smrt-network-1.12.2.jar"), plan.helperKeepGlobs, "helper kept by exact filename")
    }

    @Test
    fun `helper on but unresolvable still strips Smarty and protects the on-disk helper`() = runTest {
        // The fix: a failed resolve must NOT re-admit the proprietary Smarty jar
        // nor drop the helper-protection. Smarty is stripped via the default glob,
        // injectJar is null (nothing fetched this launch), but helperKeepGlobs is
        // still set so strict verification keeps a previously-injected helper.
        val plan = plannerResolving(null).plan(
            server(), manifest(),
            SettingsData(useOpenSmrtHelper = true, strictModVerification = false),
        )

        assertEquals(setOf("Smarty-1.12.2.jar"), plan.ignoredAddon, "Smarty stays stripped even when the helper can't be fetched")
        assertNull(plan.injectJar)
        assertTrue(!plan.strict)
        assertEquals(listOf("open-smrt-network-1.12.2.jar"), plan.helperKeepGlobs, "helper protection holds across resolver failures")
    }

    @Test
    fun `helper on but no Smarty in the manifest stays inert -- mirror pack`() = runTest {
        // A mirror / Hivens pack already ships open-smrt-network and carries no
        // proprietary Smarty jar. The swap must stay inert -- injecting the helper
        // on top of the pack's own copy loads the same coremod twice.
        val resolved = OpenSmrtHelperResolver.Resolved(helperJar, listOf("Smarty*.jar"))
        val plan = plannerResolving(resolved).plan(
            server(),
            FileManifest(
                directories = mapOf(
                    "mods" to FileManifest(
                        files = mapOf(
                            "open-smrt-network-1.12.2.jar" to FileData(md5 = "a", size = 1),
                            "JEI.jar" to FileData(md5 = "b", size = 2),
                        ),
                    ),
                ),
            ),
            SettingsData(useOpenSmrtHelper = true, strictModVerification = true),
        )

        assertTrue(plan.ignoredAddon.isEmpty(), "nothing to strip when there is no Smarty")
        assertNull(plan.injectJar, "must not inject a second open-smrt helper")
        assertTrue(plan.helperKeepGlobs.isEmpty(), "no helper protection when the swap is inert")
        assertTrue(plan.strict, "strict is independent of the swap")
    }

    @Test
    fun `helper off yields no swap and no helper protection`() = runTest {
        val resolved = OpenSmrtHelperResolver.Resolved(helperJar, listOf("Smarty*.jar"))
        val plan = plannerResolving(resolved).plan(
            server(), manifest(),
            SettingsData(useOpenSmrtHelper = false, strictModVerification = true),
        )

        assertTrue(plan.ignoredAddon.isEmpty())
        assertNull(plan.injectJar)
        assertTrue(plan.strict, "strict verification is independent of the helper swap")
        assertTrue(plan.helperKeepGlobs.isEmpty(), "swap off lets a leftover helper be pruned so the upstream mod can return")
    }
}
