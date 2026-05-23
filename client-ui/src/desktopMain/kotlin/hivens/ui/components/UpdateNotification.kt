package hivens.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import hivens.core.data.LauncherUpdate
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.CelestiaTheme
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun UpdateNotification(
    update: LauncherUpdate,
    onOpenDialog: () -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    var isVisible by remember { mutableStateOf(true) }

    // Puppet: toast-style notification (above the main app). We do NOT
    // override the current screen -- the notification lives on top of
    // whichever main screen is active.
    PuppetClick("updateNotification.details", enabled = isVisible) {
        isVisible = false; onOpenDialog()
    }
    PuppetClick("updateNotification.later", enabled = isVisible && !update.isCritical) {
        isVisible = false; onDismiss()
    }

    AnimatedVisibility(
        visible = isVisible,
        enter   = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit    = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
    ) {
        Popup(
            alignment  = Alignment.TopEnd,
            properties = PopupProperties(focusable = false)
        ) {
            Box(
                modifier = Modifier
                    .padding(16.dp)
                    .width(350.dp)
                    .background(
                        color = if (update.isCritical) CelestiaTheme.colors.error.copy(alpha = 0.95f)
                        else CelestiaTheme.colors.surface,
                        shape = MaterialTheme.shapes.medium
                    )
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier          = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector        = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint               = if (update.isCritical) Color.White else CelestiaTheme.colors.primary,
                        modifier           = Modifier.size(24.dp)
                    )

                    Spacer(Modifier.width(12.dp))

                    Column(Modifier.weight(1f)) {
                        Text(
                            text       = if (update.isCritical) s.updateTitleCritical else s.updateTitle,
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color      = if (update.isCritical) Color.White else CelestiaTheme.colors.textPrimary
                        )

                        Spacer(Modifier.height(4.dp))

                        Text(
                            text  = s.updateVersion(update.version),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (update.isCritical) Color.White.copy(alpha = 0.9f)
                            else CelestiaTheme.colors.textSecondary
                        )

                        Spacer(Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick  = { isVisible = false; onOpenDialog() },
                                modifier = Modifier.background(
                                    color = if (update.isCritical) Color.White.copy(alpha = 0.2f)
                                    else CelestiaTheme.colors.primary.copy(alpha = 0.1f),
                                    shape = MaterialTheme.shapes.small
                                )
                            ) {
                                Text(
                                    s.updateDetails,
                                    color      = if (update.isCritical) Color.White else CelestiaTheme.colors.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (!update.isCritical) {
                                TextButton(onClick = { isVisible = false; onDismiss() }) {
                                    Text(s.updateLater, color = CelestiaTheme.colors.textSecondary)
                                }
                            }
                        }
                    }

                    if (!update.isCritical) {
                        Spacer(Modifier.width(8.dp))

                        Icon(
                            imageVector        = Icons.Default.Close,
                            contentDescription = s.navBack,
                            tint               = CelestiaTheme.colors.textSecondary,
                            modifier           = Modifier
                                .size(20.dp)
                                .clickable { isVisible = false; onDismiss() }
                        )
                    }
                }
            }
        }
    }

    if (!update.isCritical) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(15_000.milliseconds)
            isVisible = false
            onDismiss()
        }
    }
}
