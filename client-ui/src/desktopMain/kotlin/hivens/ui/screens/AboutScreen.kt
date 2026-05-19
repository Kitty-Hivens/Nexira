package hivens.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import hivens.config.Branding
import hivens.core.data.LauncherUpdate
import hivens.launcher.update.UpdateService
import hivens.ui.BuildConfig
import hivens.ui.components.GlassCard
import hivens.ui.components.UpdateDialog
import hivens.ui.easter.GibberishMode
import hivens.ui.easter.LocalAprilFools
import hivens.ui.generated.resources.Res
import hivens.ui.generated.resources.favicon
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory
import hivens.ui.utils.SystemActions
import java.lang.management.ManagementFactory
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val s = LocalStrings.current
    val af = LocalAprilFools.current
    val updateService: UpdateService = koinInject()
    val scope = rememberCoroutineScope()

    var updateState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }

    // When true, the full UpdateDialog is shown -- same dialog used by UpdateManager
    var showUpdateDialog by remember { mutableStateOf(false) }

    // ── System Info pre-computation ──────────────────────────────────────────
    val systemRam = remember {
        try {
            val osBean = ManagementFactory.getOperatingSystemMXBean()
            val method = osBean.javaClass.getMethod("getTotalPhysicalMemorySize")
            method.isAccessible = true
            ((method.invoke(osBean) as Long) / (1024 * 1024)).toInt()
        } catch (_: Exception) { 0 }
    }

    val displayRes = remember {
        try {
            val size = java.awt.Toolkit.getDefaultToolkit().screenSize
            "${size.width}x${size.height}"
        } catch (_: Exception) { "Unknown" }
    }

    // ── Update dialog (reuses the existing UpdateDialog composable) ────────
    val availableUpdate = (updateState as? UpdateCheckState.Available)?.update
    if (showUpdateDialog && availableUpdate != null) {
        UpdateDialog(
            update        = availableUpdate,
            updateService = updateService,
            onDismiss     = { showUpdateDialog = false }
        )
    }

    // Two call sites for the same flow: the puppet-routed
    // `about.checkUpdates` action and the ChaosButton onClick further down.
    // Local function captures `updateState`, `scope`, `updateService`, `s`
    // from the enclosing composable so each call site shrinks to a
    // one-liner.
    fun triggerUpdateCheck() {
        updateState = UpdateCheckState.Checking
        scope.launch {
            updateState = try {
                val update = updateService.checkForUpdate(force = true)
                if (update != null) UpdateCheckState.Available(update)
                else UpdateCheckState.UpToDate
            } catch (e: Exception) {
                UpdateCheckState.Error(e.message ?: s.updateErrorUnknown)
            }
        }
    }

    PuppetScreen("About")
    PuppetClick("about.back") { onBack() }
    PuppetClick("about.checkUpdates", enabled = updateState is UpdateCheckState.Idle) {
        triggerUpdateCheck()
    }
    // Reset the check state back to Idle (works for UpToDate / Available / Error).
    PuppetClick("about.checkAgain", enabled = updateState !is UpdateCheckState.Idle &&
        updateState !is UpdateCheckState.Checking) {
        updateState = UpdateCheckState.Idle
    }
    PuppetClick("about.openUpdateDialog", enabled = updateState is UpdateCheckState.Available) {
        showUpdateDialog = true
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, s.navBack, tint = CelestiaTheme.colors.textPrimary)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                s.aboutTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CelestiaTheme.colors.textPrimary
            )
        }
        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {

            // ══════════════════════════════════════════════════════════════════
            // LEFT: Info + Credits
            // April Fools: strings here may occasionally corrupt themselves.
            // Right column (system info, links) stays readable intentionally.
            // ══════════════════════════════════════════════════════════════════
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Logo card
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = painterResource(Res.drawable.favicon),
                            contentDescription = "App Logo",
                            modifier = Modifier.size(86.dp)
                        )
                        Spacer(Modifier.height(16.dp))

                        // App title -- low probability corruption
                        Text(
                            af.maybeGibberish(Branding.TITLE, probability = 0.15f),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            color = CelestiaTheme.colors.textPrimary
                        )
                        Spacer(Modifier.height(8.dp))

                        // Version badge -- fake version numbers
                        Box(
                            Modifier.clip(RoundedCornerShape(6.dp))
                                .background(CelestiaTheme.colors.primary.copy(alpha = 0.15f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                af.maybeGibberish(
                                    "v${Branding.VERSION.removePrefix("v")}",
                                    probability = 0.30f,
                                    mode = GibberishMode.FAKE_VER
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = CelestiaTheme.colors.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        val buildDate = remember {
                            try {
                                SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                                    .format(Date(BuildConfig.BUILD_TIME))
                            } catch (_: Exception) { "--" }
                        }

                        // Build date -- may corrupt to lorem or reversed
                        Text(
                            af.maybeGibberish(s.aboutBuildDate(buildDate), probability = 0.25f),
                            style = MaterialTheme.typography.bodySmall,
                            color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(16.dp))

                        // Description -- may become jargon
                        Text(
                            af.maybeGibberish(
                                s.aboutDescription(Branding.UPSTREAM_NAME),
                                probability = 0.20f
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = CelestiaTheme.colors.textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Credits
                GlassCard(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                        SectionLabel(s.aboutSectionCreator)
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = "https://github.com/Kitty-Hivens.png?size=256",
                                contentDescription = "Haru",
                                modifier = Modifier.size(46.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop,
                                filterQuality = FilterQuality.High
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Haru (Hivens)",
                                    fontWeight = FontWeight.Bold,
                                    color = CelestiaTheme.colors.textPrimary
                                )
                                // Role -- scrambled on April Fools
                                Text(
                                    af.maybeGibberish(
                                        "Architect & Developer",
                                        probability = 0.35f,
                                        mode = GibberishMode.SCRAMBLED
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CelestiaTheme.colors.primary
                                )
                            }
                        }

                        Spacer(Modifier.height(20.dp))
                        SectionLabel(s.aboutSectionTechnologies)
                        Spacer(Modifier.height(8.dp))

                        val techs = listOf(
                            "Kotlin ${KotlinVersion.CURRENT}" to s.techKotlinDesc,
                            "Compose ${BuildConfig.COMPOSE_VERSION}" to s.techComposeDesc,
                            "Ktor ${BuildConfig.KTOR_VERSION}" to s.techKtorDesc,
                            "Koin ${BuildConfig.KOIN_VERSION}" to s.techKoinDesc,
                            "Coil ${BuildConfig.COIL_VERSION}" to s.techCoilDesc,
                            "Skia (Skiko)" to s.techSkiaDesc
                        )

                        techs.forEach { (name, desc) ->
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Text("•", color = CelestiaTheme.colors.primary, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                // Tech name -- may zalgo-ify
                                Text(
                                    af.maybeGibberish(name, probability = 0.20f, mode = GibberishMode.ZALGO),
                                    fontWeight = FontWeight.Medium,
                                    color = CelestiaTheme.colors.textPrimary,
                                    fontSize = 13.sp
                                )
                                Spacer(Modifier.width(6.dp))
                                // Description -- may become jargon
                                Text(
                                    "-- ${af.maybeGibberish(desc, probability = 0.40f, mode = GibberishMode.JARGON)}",
                                    color = CelestiaTheme.colors.textSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        Spacer(Modifier.height(20.dp))
                        SectionLabel(s.aboutSectionLicense)
                        Spacer(Modifier.height(8.dp))

                        // License text -- high probability lorem ipsum
                        Text(
                            af.maybeGibberish(
                                s.aboutLicenseText,
                                probability = 0.45f,
                                mode = GibberishMode.LOREM
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = CelestiaTheme.colors.textSecondary
                        )
                    }
                }
            }

            // ══════════════════════════════════════════════════════════════════
            // RIGHT: Updates + System + Links
            // No gibberish here -- user needs to be able to read system info
            // and actually click the links when desperate enough.
            // ══════════════════════════════════════════════════════════════════
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Updates ────────────────────────────────────────────────────
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        SectionLabel(s.aboutSectionUpdates)
                        Spacer(Modifier.height(16.dp))
                        InfoRow(Icons.Default.Info, s.aboutCurrentVersion, "v${Branding.VERSION.removePrefix("v")}")
                        Spacer(Modifier.height(16.dp))

                        when (val state = updateState) {
                            // ── Idle: check button -- chaos target ─────────────
                            UpdateCheckState.Idle -> {
                                af.ChaosButton(
                                    id      = "about_check_updates_btn",
                                    text    = s.aboutCheckUpdates,
                                    onClick = { triggerUpdateCheck() },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors   = ButtonDefaults.buttonColors(
                                        containerColor = CelestiaTheme.colors.primary,
                                    ),
                                )
                            }

                            // ── Checking: spinner -- not chaos, user needs feedback
                            UpdateCheckState.Checking -> {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = CelestiaTheme.colors.primary
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(s.aboutChecking, color = CelestiaTheme.colors.textSecondary)
                                }
                            }

                            // ── Up to date ────────────────────────────────────
                            UpdateCheckState.UpToDate -> {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(CelestiaTheme.colors.success.copy(alpha = 0.1f))
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CheckCircle, null, tint = CelestiaTheme.colors.success, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(s.aboutUpToDate, color = CelestiaTheme.colors.success, fontWeight = FontWeight.Medium)
                                }
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { updateState = UpdateCheckState.Idle }) {
                                    Text(s.aboutCheckAgain, color = CelestiaTheme.colors.textSecondary, fontSize = 12.sp)
                                }
                            }

                            // ── Available: info + download button ─────────────
                            is UpdateCheckState.Available -> {
                                Column(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(CelestiaTheme.colors.primary.copy(alpha = 0.08f))
                                        .padding(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.NewReleases, null, tint = CelestiaTheme.colors.primary, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            s.aboutUpdateAvailable(state.update.version),
                                            fontWeight = FontWeight.Bold,
                                            color = CelestiaTheme.colors.primary
                                        )
                                    }
                                    if (state.update.isCritical) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "⚠ ${s.aboutCriticalUpdate}",
                                            fontSize = 12.sp,
                                            color = CelestiaTheme.colors.error,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Spacer(Modifier.height(12.dp))

                                // Download & install -- opens the full UpdateDialog
                                Button(
                                    onClick = { showUpdateDialog = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (state.update.isCritical)
                                            CelestiaTheme.colors.error
                                        else
                                            CelestiaTheme.colors.primary
                                    )
                                ) {
                                    Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (state.update.isCritical) s.updateDownloadNow else s.updateDownload,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                TextButton(onClick = { updateState = UpdateCheckState.Idle }) {
                                    Text(s.aboutCheckAgain, color = CelestiaTheme.colors.textSecondary, fontSize = 12.sp)
                                }
                            }

                            // ── Error ─────────────────────────────────────────
                            is UpdateCheckState.Error -> {
                                Column(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(CelestiaTheme.colors.error.copy(alpha = 0.08f))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        s.stateError(state.message),
                                        color = CelestiaTheme.colors.error,
                                        fontSize = 13.sp
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { updateState = UpdateCheckState.Idle }) {
                                    Text(s.updateRetry, color = CelestiaTheme.colors.textSecondary)
                                }
                            }
                        }
                    }
                }

                // ── System ─────────────────────────────────────────────────────
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        SectionLabel(s.aboutSectionSystem)
                        Spacer(Modifier.height(12.dp))

                        val osName     = System.getProperty("os.name")
                        val osArch     = System.getProperty("os.arch")
                        val osVer      = System.getProperty("os.version")
                        val javaVer    = System.getProperty("java.version")
                        val javaVendor = System.getProperty("java.vendor")
                        val cores      = Runtime.getRuntime().availableProcessors()
                        val maxHeap    = Runtime.getRuntime().maxMemory() / (1024 * 1024)

                        InfoRow(Icons.Default.Computer, s.aboutOs, "$osName $osVer ($osArch)")
                        InfoRow(Icons.Default.Memory,   "CPU",     "$cores threads")
                        InfoRow(Icons.Default.Storage,  "RAM",     "${if (systemRam > 0) "$systemRam MB" else "Unknown"} (Heap: $maxHeap MB)")
                        InfoRow(Icons.Default.Code,     "Java",    "$javaVer ($javaVendor)")
                        InfoRow(Icons.Default.Tv,       "Display", displayRes)
                    }
                }

                // ── Links ──────────────────────────────────────────────────────
                GlassCard(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.padding(20.dp)) {
                        SectionLabel(s.aboutSectionLinks)
                        Spacer(Modifier.height(12.dp))
                        LinkButton(s.aboutLinkGithub,    "https://github.com/Kitty-Hivens/Aura-Launcher",         Icons.Default.Code)
                        Spacer(Modifier.height(8.dp))
                        LinkButton(s.aboutLinkBugReport, "https://github.com/Kitty-Hivens/Aura-Launcher/issues",  Icons.Default.BugReport)
                        Spacer(Modifier.height(8.dp))
                        LinkButton(s.aboutLinkReleases,  "https://github.com/Kitty-Hivens/Aura-Launcher/releases",Icons.Default.Download)
                    }
                }
            }
        }
    }
}

// ── State ─────────────────────────────────────────────────────────────────────

private sealed class UpdateCheckState {
    object Idle     : UpdateCheckState()
    object Checking : UpdateCheckState()
    object UpToDate : UpdateCheckState()
    data class Available(val update: LauncherUpdate) : UpdateCheckState()
    data class Error(val message: String)            : UpdateCheckState()
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style         = MaterialTheme.typography.labelSmall,
        fontWeight    = FontWeight.Bold,
        color         = CelestiaTheme.colors.primary,
        letterSpacing = 1.sp
    )
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = CelestiaTheme.colors.textSecondary.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = CelestiaTheme.colors.textSecondary, fontSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color      = CelestiaTheme.colors.textPrimary,
            fontSize   = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            textAlign  = TextAlign.End,
            modifier   = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LinkButton(label: String, url: String, icon: ImageVector) {
    OutlinedButton(
        onClick = {
            SystemActions.openUrl(url)
        },
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(8.dp),
        border   = BorderStroke(1.dp, CelestiaTheme.colors.outline.copy(alpha = 0.2f))
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = CelestiaTheme.colors.primary)
        Spacer(Modifier.width(8.dp))
        Text(label, color = CelestiaTheme.colors.textPrimary)
        Spacer(Modifier.weight(1f))
        Text("↗", color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.4f))
    }
}
