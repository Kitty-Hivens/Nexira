package hivens.ui.widgets.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.jvm.SystemHardware
import hivens.core.jvm.SystemMemory
import hivens.launcher.update.UpdateService
import hivens.ui.components.UpdateDialog
import hivens.ui.components.UpdateManagerDialog
import hivens.ui.i18n.LocalStrings
import hivens.ui.layout.AdaptiveWidth
import hivens.ui.layout.WidthClass
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.CelestiaTheme
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val SURFACE = "about"

// about surface. AppLayout routes Screen.About here. Two
// side-by-side slots: `left` (logo + credits) and `right` (update
// panel, system rows, links). Chrome stays surface-level (back +
// title) -- per the carve-out rule, removing them orphans the
// screen.
//
// Surface owns the per-screen state (updateState, showUpdateDialog)
// and the asynchronous triggerUpdateCheck closure; widgets observe
// MutableState holders in the context and re-render. The full
// UpdateDialog modal lives at the surface level so it survives even
// if the user removes the update.panel widget via the editor.
//
// Pre-computed values (systemRam lookup, displayRes AWT toolkit
// call) happen once at surface mount and pass into the
// context -- per-widget recomputation would re-read these on every
// recomposition.
//
// No verticalScroll on the slot containers; the credits widget
// self-scrolls its long body text. Lazy widgets the user may drop
// via the editor render with bounded constraints.
@Composable
fun AboutSurface(onBack: () -> Unit) {
    val s = LocalStrings.current
    val updateService: UpdateService = koinInject()
    val scope = rememberCoroutineScope()

    val updateState       = remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
    val showUpdateDialog  = remember { mutableStateOf(false) }
    val showUpdateManager = remember { mutableStateOf(false) }

    // OrNull (not the sizing fallback): the System-info card shows "Unknown" on a 0,
    // so a broken runtime reads as unknown rather than a fabricated 16 GB.
    val systemRam = remember { SystemMemory.totalPhysicalMbOrNull() ?: 0 }
    val swapMb    = remember { SystemHardware.swapTotalMb }
    val cpu       = remember { SystemHardware.cpu }

    val displayInfo = remember {
        runCatching {
            val tk   = java.awt.Toolkit.getDefaultToolkit()
            val size = tk.screenSize
            val dpi  = tk.screenResolution
            val scale = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
            val scaleStr = if (scale % 1.0 == 0.0) "${scale.toInt()}x" else "%.2fx".format(scale)
            "${size.width}x${size.height} · $dpi dpi · $scaleStr"
        }.getOrDefault("Unknown")
    }

    // Renderer "future hook". Skiko has no native Wayland yet: on a Wayland session
    // it renders through XWayland, i.e. still Xorg -- so report Xorg (noting
    // XWayland) rather than the session type. Native Wayland is the future hook;
    // flip this when Skiko-on-Wayland lands. The render API comes from the skiko
    // system property when pinned (else "default" -- Skiko picks at runtime).
    val renderer = remember {
        val windowing = when {
            System.getenv("DISPLAY") != null ->
                if (System.getenv("WAYLAND_DISPLAY") != null) "Xorg (XWayland)" else "Xorg"
            else -> System.getProperty("os.name") ?: "?"
        }
        val api = System.getProperty("skiko.renderApi")?.takeIf { it.isNotBlank() } ?: "default"
        "$windowing · $api"
    }

    val unknownErrorText = s.updateErrorUnknown
    val triggerUpdateCheck: () -> Unit = remember(scope, updateService, unknownErrorText) {
        {
            updateState.value = UpdateCheckState.Checking
            scope.launch {
                updateState.value = try {
                    val update = updateService.checkForUpdate()
                    if (update != null) UpdateCheckState.Available(update)
                    else UpdateCheckState.UpToDate
                } catch (e: Exception) {
                    UpdateCheckState.Error(e.message ?: unknownErrorText)
                }
            }
        }
    }

    val ctx = remember(updateState, showUpdateDialog, showUpdateManager, triggerUpdateCheck, systemRam, swapMb, cpu, displayInfo, renderer) {
        AboutContext(
            updateState        = updateState,
            showUpdateDialog   = showUpdateDialog,
            showUpdateManager  = showUpdateManager,
            triggerUpdateCheck = triggerUpdateCheck,
            systemRam          = systemRam,
            swapMb             = swapMb,
            cpu                = cpu,
            displayInfo        = displayInfo,
            renderer           = renderer,
        )
    }

    // Background auto-check every 5 minutes while the About screen is open,
    // stopping once an update is found (the panel then shows it). 12 checks/hour
    // sits comfortably under GitHub's ~60/hour unauthenticated API ceiling.
    LaunchedEffect(Unit) {
        while (true) {
            // Skip when a result is already shown (Available) or a check is
            // still running (Checking) -- a tick that lands on an in-flight or
            // manual check would otherwise launch a second concurrent
            // checkForUpdate that races to overwrite updateState.
            val state = updateState.value
            if (state !is UpdateCheckState.Available && state !is UpdateCheckState.Checking) {
                triggerUpdateCheck()
            }
            delay(5 * 60_000L)
        }
    }

    // Modal update dialog -- mounted at surface level so it persists
    // even if the user removes the update.panel widget. Available
    // state is the only branch that can open it.
    val availableUpdate = (updateState.value as? UpdateCheckState.Available)?.update
    if (showUpdateDialog.value && availableUpdate != null) {
        UpdateDialog(
            update        = availableUpdate,
            updateService = updateService,
            onDismiss     = { showUpdateDialog.value = false },
        )
    }

    // Full update manager (channels, version picker/rollback, .desktop,
    // build-from-source) -- opened from the version "i" in the update panel.
    if (showUpdateManager.value) {
        UpdateManagerDialog(onDismiss = { showUpdateManager.value = false })
    }

    PuppetScreen("About")
    PuppetClick("about.openUpdateManager") { showUpdateManager.value = true }
    PuppetClick("about.back") { onBack() }
    PuppetClick("about.checkUpdates", enabled = updateState.value is UpdateCheckState.Idle) {
        triggerUpdateCheck()
    }
    PuppetClick(
        id      = "about.checkAgain",
        enabled = updateState.value !is UpdateCheckState.Idle &&
                  updateState.value !is UpdateCheckState.Checking,
    ) {
        updateState.value = UpdateCheckState.Idle
    }
    PuppetClick("about.openUpdateDialog", enabled = updateState.value is UpdateCheckState.Available) {
        showUpdateDialog.value = true
    }

    CompositionLocalProvider(LocalAboutContext provides ctx) {
        AdaptiveWidth(Modifier.fillMaxSize()) { widthClass, _ ->
            val pad = when (widthClass) {
                WidthClass.Expanded -> 24.dp
                WidthClass.Medium   -> 16.dp
                WidthClass.Compact  -> 12.dp
            }
            Column(Modifier.fillMaxSize().padding(pad)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = s.navBack,
                            tint               = CelestiaTheme.colors.textPrimary,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text       = s.aboutTitle,
                        style      = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color      = CelestiaTheme.colors.textPrimary,
                    )
                }
                Spacer(Modifier.height(if (widthClass == WidthClass.Expanded) 20.dp else 12.dp))

                if (widthClass == WidthClass.Expanded) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        SlotRenderer(
                            SurfaceId(SURFACE),
                            SlotId("left"),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            spacing  = 16.dp,
                        )
                        SlotRenderer(
                            SurfaceId(SURFACE),
                            SlotId("right"),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            spacing  = 16.dp,
                        )
                    }
                } else {
                    // Too narrow to split side by side: stack the columns. Each
                    // half stays height-bounded (weight) so the credits widget's
                    // own scroll keeps working; the right half has no inner scroll,
                    // so it gets one to keep its fixed cards reachable. (A single
                    // outer scroll would collide with the credits' inner scroll.)
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SlotRenderer(
                            SurfaceId(SURFACE),
                            SlotId("left"),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            spacing  = 16.dp,
                        )
                        SlotRenderer(
                            SurfaceId(SURFACE),
                            SlotId("right"),
                            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            spacing  = 16.dp,
                        )
                    }
                }
            }
        }
    }
}
