package hivens.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import hivens.core.data.LauncherUpdate
import hivens.ui.theme.CelestiaTheme

/**
 * Небольшое всплывающее уведомление об обновлении (появляется в правом верхнем углу).
 */
@Composable
fun UpdateNotification(
    update: LauncherUpdate,
    onOpenDialog: () -> Unit,
    onDismiss: () -> Unit
) {
    var isVisible by remember { mutableStateOf(true) }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
    ) {
        Popup(
            alignment = Alignment.TopEnd,
            properties = PopupProperties(focusable = false),
            onDismissRequest = { }
        ) {
            Box(
                modifier = Modifier
                    .padding(16.dp)
                    .width(350.dp)
                    .background(
                        color = if (update.isCritical) {
                            CelestiaTheme.colors.error.copy(alpha = 0.95f)
                        } else {
                            CelestiaTheme.colors.surface
                        },
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = if (update.isCritical) Color.White else CelestiaTheme.colors.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    
                    Spacer(Modifier.width(12.dp))
                    
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (update.isCritical) {
                                "Критическое обновление!"
                            } else {
                                "Доступно обновление"
                            },
                            style = MaterialTheme.typography.subtitle1,
                            fontWeight = FontWeight.Bold,
                            color = if (update.isCritical) Color.White else CelestiaTheme.colors.textPrimary
                        )
                        
                        Spacer(Modifier.height(4.dp))
                        
                        Text(
                            text = "Версия ${update.version}",
                            style = MaterialTheme.typography.body2,
                            color = if (update.isCritical) {
                                Color.White.copy(alpha = 0.9f)
                            } else {
                                CelestiaTheme.colors.textSecondary
                            }
                        )
                        
                        Spacer(Modifier.height(12.dp))
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    isVisible = false
                                    onOpenDialog()
                                },
                                modifier = Modifier.background(
                                    color = if (update.isCritical) {
                                        Color.White.copy(alpha = 0.2f)
                                    } else {
                                        CelestiaTheme.colors.primary.copy(alpha = 0.1f)
                                    },
                                    shape = RoundedCornerShape(6.dp)
                                )
                            ) {
                                Text(
                                    "Подробнее",
                                    color = if (update.isCritical) Color.White else CelestiaTheme.colors.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            if (!update.isCritical) {
                                TextButton(
                                    onClick = {
                                        isVisible = false
                                        onDismiss()
                                    }
                                ) {
                                    Text("Позже", color = CelestiaTheme.colors.textSecondary)
                                }
                            }
                        }
                    }
                    
                    if (!update.isCritical) {
                        Spacer(Modifier.width(8.dp))
                        
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Закрыть",
                            tint = CelestiaTheme.colors.textSecondary,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable {
                                    isVisible = false
                                    onDismiss()
                                }
                        )
                    }
                }
            }
        }
    }

    // Auto-dismiss non-critical notifications after 15 seconds
    if (!update.isCritical) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(15000)
            isVisible = false
            onDismiss()
        }
    }
}
