package hivens.ui.widgets.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.config.Branding
import hivens.ui.components.GlassCard
import hivens.ui.easter.LocalAprilFools
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class AboutUpdateProps(
    @PropLabel("widget.about.update.panel.title") val title: String = "",
)

// Update check + state machine: Idle (check button) -> Checking
// (spinner) -> UpToDate / Available / Error. Reads + writes
// ctx.updateState; opens the modal UpdateDialog via
// ctx.showUpdateDialog. Removing this widget loses the in-pane
// update affordance but the puppet check / dialog routes still
// work because the state lives in the surface composable.
@Widget(id = "about.update.panel", displayName = "widget.about.update.panel", propsClass = AboutUpdateProps::class)
@Composable
fun AboutUpdatePanelWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<AboutUpdateProps>()
    val ctx = LocalAboutContext.current
    val af = LocalAprilFools.current
    val s = LocalStrings.current
    val state by ctx.updateState

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            SectionLabel(p.title.ifBlank { s.aboutSectionUpdates })
            Spacer(Modifier.height(16.dp))
            InfoRow(Icons.Default.Info, s.aboutCurrentVersion, "v${Branding.VERSION.removePrefix("v")}")
            Spacer(Modifier.height(16.dp))

            when (val current = state) {
                UpdateCheckState.Idle -> {
                    af.ChaosButton(
                        id      = "about_check_updates_btn",
                        text    = s.aboutCheckUpdates,
                        onClick = { ctx.triggerUpdateCheck() },
                        modifier = Modifier.fillMaxWidth(),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = CelestiaTheme.colors.primary,
                        ),
                    )
                }

                UpdateCheckState.Checking -> {
                    Row(
                        modifier              = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color       = CelestiaTheme.colors.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(s.aboutChecking, color = CelestiaTheme.colors.textSecondary)
                    }
                }

                UpdateCheckState.UpToDate -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CelestiaTheme.colors.success.copy(alpha = 0.1f))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = CelestiaTheme.colors.success, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(s.aboutUpToDate, color = CelestiaTheme.colors.success, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { ctx.updateState.value = UpdateCheckState.Idle }) {
                        Text(s.aboutCheckAgain, color = CelestiaTheme.colors.textSecondary, fontSize = 12.sp)
                    }
                }

                is UpdateCheckState.Available -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CelestiaTheme.colors.primary.copy(alpha = 0.08f))
                            .padding(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.NewReleases, null, tint = CelestiaTheme.colors.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text       = s.aboutUpdateAvailable(current.update.version),
                                fontWeight = FontWeight.Bold,
                                color      = CelestiaTheme.colors.primary,
                            )
                        }
                        if (current.update.isCritical) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text       = "⚠ ${s.aboutCriticalUpdate}",
                                fontSize   = 12.sp,
                                color      = CelestiaTheme.colors.error,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick  = { ctx.showUpdateDialog.value = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape    = MaterialTheme.shapes.small,
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = if (current.update.isCritical)
                                CelestiaTheme.colors.error
                            else
                                CelestiaTheme.colors.primary,
                        ),
                    ) {
                        Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text       = if (current.update.isCritical) s.updateDownloadNow else s.updateDownload,
                            fontWeight = FontWeight.Bold,
                            color      = Color.White,
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    TextButton(onClick = { ctx.updateState.value = UpdateCheckState.Idle }) {
                        Text(s.aboutCheckAgain, color = CelestiaTheme.colors.textSecondary, fontSize = 12.sp)
                    }
                }

                is UpdateCheckState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CelestiaTheme.colors.error.copy(alpha = 0.08f))
                            .padding(12.dp),
                    ) {
                        Text(
                            text     = s.stateError(current.message),
                            color    = CelestiaTheme.colors.error,
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { ctx.updateState.value = UpdateCheckState.Idle }) {
                        Text(s.updateRetry, color = CelestiaTheme.colors.textSecondary)
                    }
                }
            }
        }
    }
}
