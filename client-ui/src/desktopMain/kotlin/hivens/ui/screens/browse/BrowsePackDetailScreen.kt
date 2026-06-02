package hivens.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.launcher.PackInstaller
import hivens.launcher.smrt.SmrtPackClient
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Catalog inspect page for a single mirror-published pack.
 * Modrinth-pattern: hero with banner + tagline up top, main column
 * carries the install CTA + any future long-form description block,
 * sidebar carries the structured metadata (compatibility, version,
 * tags, links).
 *
 * Intentionally NO mod / asset browser here. Catalog inspect answers
 * "what is this and do I want it"; per-item content management is a
 * concern of the installed-instance pack page (Library), not the
 * catalog. Putting the browser here mixed the two surfaces and got
 * reverted before the PR landed.
 *
 * Three states: [DetailState.Loading], [DetailState.Loaded] (summary
 * + manifest both fetched in parallel), [DetailState.Error] with
 * retry.
 */
@Composable
fun BrowsePackDetailScreen(
    packId: String,
    onBack: () -> Unit,
    onInstalled: (instanceId: String) -> Unit,
) {
    PuppetScreen("BrowsePackDetail.$packId")
    PuppetClick("browse.detail.back") { onBack() }

    val s = LocalStrings.current
    val client: SmrtPackClient = koinInject()
    val installer: PackInstaller = koinInject()
    val scope = rememberCoroutineScope()
    var state by remember(packId) { mutableStateOf<DetailState>(DetailState.Loading) }
    var installState by remember(packId) { mutableStateOf<InstallState>(InstallState.Idle) }
    var retryTick by remember { mutableIntStateOf(0) }

    PuppetClick("browse.detail.retry") { retryTick++ }

    LaunchedEffect(packId, retryTick) {
        state = DetailState.Loading
        state = try {
            val pair = coroutineScope {
                val summary  = async(Dispatchers.IO) { client.fetchSummary(packId) }
                val manifest = async(Dispatchers.IO) { client.fetchManifest(packId) }
                awaitAll(summary, manifest)
                summary.await() to manifest.await()
            }
            DetailState.Loaded(pair.first, pair.second)
        } catch (e: Exception) {
            DetailState.Error(e.message ?: s.browseDetailErrorMessage)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Hero(packId = packId, summary = (state as? DetailState.Loaded)?.summary, onBack = onBack)

        when (val st = state) {
            DetailState.Loading -> Box(
                Modifier.fillMaxWidth().height(280.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color       = CelestiaTheme.colors.primary.copy(alpha = 0.6f),
                    strokeWidth = 2.dp,
                    modifier    = Modifier.size(28.dp),
                )
            }

            is DetailState.Error -> Box(
                Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text       = s.browseDetailErrorTitle,
                        style      = MaterialTheme.typography.titleLarge,
                        color      = CelestiaTheme.colors.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text      = st.message,
                        style     = MaterialTheme.typography.bodySmall,
                        color     = CelestiaTheme.colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.widthIn(max = 480.dp),
                    )
                    Button(onClick = { retryTick++ }) { Text(s.browseRetry) }
                }
            }

            is DetailState.Loaded -> LoadedBody(
                summary       = st.summary,
                manifest      = st.manifest,
                installState  = installState,
                onInstall     = {
                    if (installState !is InstallState.Running) {
                        scope.launch {
                            installState = InstallState.Running(0, 0, "")
                            installState = try {
                                val inst = installer.install(
                                    packId   = packId,
                                    summary  = st.summary,
                                    manifest = st.manifest,
                                ) { current, total, fn ->
                                    installState = InstallState.Running(current, total, fn)
                                }
                                InstallState.Done(inst.id)
                            } catch (e: Exception) {
                                InstallState.Failed(e.message ?: s.browseDetailInstallFailedGeneric)
                            }
                        }
                    }
                },
                onOpenInstalled = { instanceId -> onInstalled(instanceId) },
                onRetryInstall  = { installState = InstallState.Idle },
            )
        }
    }
}

@Composable
private fun Hero(packId: String, summary: SmrtPackSummary?, onBack: () -> Unit) {
    val gradient = Brush.linearGradient(listOf(Color(0xFF1E3A8A), Color(0xFF1D4ED8)))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(gradient),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))

        IconButton(
            onClick  = onBack,
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
        }

        Column(
            modifier            = Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(
                text       = summary?.displayName ?: packId,
                style      = MaterialTheme.typography.headlineLarge,
                color      = Color.White,
                fontWeight = FontWeight.Bold,
            )
            if (summary != null && summary.tagline.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = summary.tagline,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.88f),
                )
            }
        }
    }
}

@Composable
private fun LoadedBody(
    summary: SmrtPackSummary,
    manifest: SmrtPackManifest,
    installState: InstallState,
    onInstall: () -> Unit,
    onOpenInstalled: (instanceId: String) -> Unit,
    onRetryInstall: () -> Unit,
) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Main column: install CTA + (future) long-form description.
        Column(
            modifier            = Modifier.weight(2f),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            InstallBar(
                installState    = installState,
                onInstall       = onInstall,
                onOpenInstalled = onOpenInstalled,
                onRetry         = onRetryInstall,
            )
            DescriptionPlaceholder(modsCount = manifest.mods.size, assetsCount = manifest.assets.size)
        }
        // Sidebar: structured metadata.
        Column(
            modifier            = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            CompatBlock(manifest = manifest)
            VersionBlock(version = manifest.packVersion)
            if (summary.tags.isNotEmpty()) TagsBlock(tags = summary.tags)
        }
    }
}

@Composable
private fun InstallBar(
    installState: InstallState,
    onInstall: () -> Unit,
    onOpenInstalled: (instanceId: String) -> Unit,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(glassSurfaceAlpha(0.6f))
            .padding(20.dp),
    ) {
        when (installState) {
            InstallState.Idle              -> InstallIdleRow(onInstall = onInstall)
            is InstallState.Running        -> InstallRunningRow(installState)
            is InstallState.Done           -> InstallDoneRow(instanceId = installState.instanceId, onOpenInstalled = onOpenInstalled)
            is InstallState.Failed         -> InstallFailedRow(message = installState.message, onRetry = onRetry)
        }
    }
}

@Composable
private fun InstallIdleRow(onInstall: () -> Unit) {
    val s = LocalStrings.current
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = s.browseDetailInstallReady,
                style      = MaterialTheme.typography.titleMedium,
                color      = CelestiaTheme.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text  = s.browseDetailInstallHint,
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textSecondary,
            )
        }
        Button(
            onClick        = onInstall,
            shape          = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            colors         = ButtonDefaults.buttonColors(
                containerColor = CelestiaTheme.colors.primary,
                contentColor   = Color.White,
            ),
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(LocalStrings.current.browseDetailInstallButton, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun InstallRunningRow(state: InstallState.Running) {
    val s = LocalStrings.current
    val fraction = if (state.total > 0) state.current.toFloat() / state.total else 0f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text       = s.browseDetailInstallRunningTitle,
            style      = MaterialTheme.typography.titleMedium,
            color      = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.total > 0) {
            LinearProgressIndicator(
                progress  = { fraction },
                modifier  = Modifier.fillMaxWidth(),
                color     = CelestiaTheme.colors.primary,
                trackColor = CelestiaTheme.colors.outline.copy(alpha = 0.25f),
            )
            Text(
                text  = s.browseDetailInstallProgress.format(state.filename, state.current, state.total),
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textSecondary,
            )
        } else {
            // total == 0 happens during the brief window between
            // sync start and the first progress emit. Indeterminate
            // bar avoids flashing a 0% fill.
            LinearProgressIndicator(
                modifier  = Modifier.fillMaxWidth(),
                color     = CelestiaTheme.colors.primary,
                trackColor = CelestiaTheme.colors.outline.copy(alpha = 0.25f),
            )
            Text(
                text  = s.browseDetailInstallStarting,
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun InstallDoneRow(instanceId: String, onOpenInstalled: (String) -> Unit) {
    val s = LocalStrings.current
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = s.browseDetailInstallDoneTitle,
                style      = MaterialTheme.typography.titleMedium,
                color      = CelestiaTheme.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text  = s.browseDetailInstallDoneHint,
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textSecondary,
            )
        }
        Button(
            onClick        = { onOpenInstalled(instanceId) },
            shape          = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            colors         = ButtonDefaults.buttonColors(
                containerColor = CelestiaTheme.colors.primary,
                contentColor   = Color.White,
            ),
        ) { Text(s.browseDetailInstallOpenLibrary, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun InstallFailedRow(message: String, onRetry: () -> Unit) {
    val s = LocalStrings.current
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = s.browseDetailInstallFailedTitle,
                style      = MaterialTheme.typography.titleMedium,
                color      = CelestiaTheme.colors.error,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text  = message,
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textSecondary,
            )
        }
        Button(
            onClick        = onRetry,
            shape          = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            colors         = ButtonDefaults.buttonColors(
                containerColor = CelestiaTheme.colors.primary,
                contentColor   = Color.White,
            ),
        ) { Text(s.browseRetry, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun DescriptionPlaceholder(modsCount: Int, assetsCount: Int) {
    val s = LocalStrings.current
    Section(title = s.browseDetailAboutTitle) {
        Text(
            text  = s.browseDetailAboutPlaceholder.format(modsCount, assetsCount),
            style = MaterialTheme.typography.bodyMedium,
            color = CelestiaTheme.colors.textPrimary.copy(alpha = 0.9f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text  = s.browseDetailAboutNote,
            style = MaterialTheme.typography.bodySmall,
            color = CelestiaTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun CompatBlock(manifest: SmrtPackManifest) {
    val s = LocalStrings.current
    SidebarBlock(title = s.browseDetailCompatTitle) {
        MetaRow(label = s.browseDetailCompatMc,     value = manifest.minecraft.version)
        MetaRow(label = s.browseDetailCompatLoader, value = "${manifest.loader.name} ${manifest.loader.version}")
        MetaRow(label = s.browseDetailCompatJava,   value = "Java ${manifest.java.major}")
    }
}

@Composable
private fun VersionBlock(version: String) {
    val s = LocalStrings.current
    SidebarBlock(title = s.browseDetailVersionTitle) {
        Text(
            text       = version,
            style      = MaterialTheme.typography.bodyMedium,
            color      = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TagsBlock(tags: List<String>) {
    val s = LocalStrings.current
    SidebarBlock(title = s.browseDetailTagsTitle) {
        // Inline flow of chips. Compose Material3 does not have a
        // FlowRow primitive that handles wrap-around for arbitrary
        // chip widths nicely at all sizes; an explicit Row that
        // overflows for very wide tag sets is acceptable for now --
        // tag lists in practice are 2-4 short strings.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.forEach { Chip(it) }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            color = CelestiaTheme.colors.textSecondary,
        )
        Text(
            text       = value,
            style      = MaterialTheme.typography.bodySmall,
            color      = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text          = title,
            style         = MaterialTheme.typography.titleSmall,
            color         = CelestiaTheme.colors.primary,
            fontWeight    = FontWeight.Bold,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(glassSurfaceAlpha(0.6f))
                .padding(16.dp),
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun SidebarBlock(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text       = title,
            style      = MaterialTheme.typography.titleSmall,
            color      = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun Chip(text: String) {
    AssistChip(
        onClick = {},
        enabled = false,
        shape   = RoundedCornerShape(8.dp),
        label   = { Text(text, style = MaterialTheme.typography.labelSmall, color = CelestiaTheme.colors.textPrimary) },
        colors  = AssistChipDefaults.assistChipColors(
            disabledContainerColor = glassSurfaceAlpha(0.4f),
            disabledLabelColor     = CelestiaTheme.colors.textPrimary,
        ),
        border  = null,
    )
}

private sealed class DetailState {
    object Loading : DetailState()
    data class Loaded(val summary: SmrtPackSummary, val manifest: SmrtPackManifest) : DetailState()
    data class Error(val message: String) : DetailState()
}

private sealed class InstallState {
    object Idle : InstallState()
    data class Running(val current: Int, val total: Int, val filename: String) : InstallState()
    data class Done(val instanceId: String) : InstallState()
    data class Failed(val message: String) : InstallState()
}
