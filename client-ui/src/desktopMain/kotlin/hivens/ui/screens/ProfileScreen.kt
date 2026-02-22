package hivens.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.api.SkinRepository
import hivens.core.data.SessionData
import hivens.ui.components.CelestiaButton
import hivens.ui.components.GlassCard
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import hivens.ui.utils.SkinManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.io.File
import java.net.URI
import javax.swing.JFrame

@Composable
fun ProfileScreen(session: SessionData, skinRepository: SkinRepository) {
    val s = LocalStrings.current
    var frontSkin by remember { mutableStateOf<ImageBitmap?>(null) }
    var backSkin by remember { mutableStateOf<ImageBitmap?>(null) }
    var uploadStatus by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    fun loadSkins() {
        scope.launch {
            frontSkin = SkinManager.getSkinFront(session.playerName)
            backSkin = SkinManager.getSkinBack(session.playerName)
        }
    }

    LaunchedEffect(Unit) { loadSkins() }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(s.profileTitle, style = MaterialTheme.typography.h4, color = CelestiaTheme.colors.textPrimary)
        Spacer(Modifier.height(24.dp))

        Row(Modifier.fillMaxSize()) {
            // Skin preview
            GlassCard(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (frontSkin != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                                Image(
                                    painter = BitmapPainter(frontSkin!!),
                                    contentDescription = s.profileSkinFront,
                                    modifier = Modifier.height(300.dp).width(150.dp)
                                )
                                if (backSkin != null) {
                                    Image(
                                        painter = BitmapPainter(backSkin!!),
                                        contentDescription = s.profileSkinBack,
                                        modifier = Modifier.height(300.dp).width(150.dp)
                                    )
                                }
                            }
                        } else {
                            Text(s.profileSkinLoading, color = CelestiaTheme.colors.textSecondary)
                        }
                    }
                    IconButton(
                        onClick = { SkinManager.invalidate(session.playerName); loadSkins() },
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    ) {
                        Icon(Icons.Default.Refresh, s.profileRefresh, tint = CelestiaTheme.colors.textPrimary)
                    }
                }
            }

            Spacer(Modifier.width(24.dp))

            // Info & actions
            Column(Modifier.width(320.dp).fillMaxHeight()) {
                GlassCard(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.padding(24.dp)) {
                        Text(session.playerName, style = MaterialTheme.typography.h5, color = CelestiaTheme.colors.textPrimary)

                        val isOnline = session.accessToken.length > 10
                        val statusText = if (isOnline) s.profileStatusOnline else s.profileStatusOffline
                        Text(
                            "${s.profileStatusLabel}: $statusText",
                            color = if (isOnline) CelestiaTheme.colors.success else CelestiaTheme.colors.error
                        )

                        Spacer(Modifier.height(24.dp))

                        // Balance
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = CelestiaTheme.colors.background.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = s.profileBalance,
                                        tint = Color(0xFFFFD700),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(s.profileBalance, color = CelestiaTheme.colors.textSecondary)
                                }
                                Text(
                                    text = "${session.balance} ⛃",
                                    style = MaterialTheme.typography.h6,
                                    fontWeight = FontWeight.Bold,
                                    color = CelestiaTheme.colors.textPrimary
                                )
                            }
                        }

                        Spacer(Modifier.height(32.dp))

                        // Upload status
                        if (uploadStatus.isNotEmpty()) {
                            Text(
                                text = uploadStatus,
                                color = if (uploadStatus.startsWith("Ошибка") || uploadStatus.startsWith("Error") || uploadStatus.startsWith("Fehler"))
                                    CelestiaTheme.colors.error
                                else
                                    CelestiaTheme.colors.success,
                                style = MaterialTheme.typography.caption,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        CelestiaButton(s.profileTopUp, onClick = {
                            try {
                                val url = "http://smartycraft.ru/cabinet"
                                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                                    Desktop.getDesktop().browse(URI(url))
                                }
                            } catch (e: Exception) { e.printStackTrace() }
                        }, modifier = Modifier.fillMaxWidth())

                        Spacer(Modifier.height(16.dp))

                        CelestiaButton(s.profileUploadSkin, onClick = {
                            val file = pickImage(s.profileUploadSkin)
                            if (file != null) {
                                uploadStatus = s.profileUploadSkinLoading
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        skinRepository.uploadSkin(file, false, session)
                                    }
                                    uploadStatus = result
                                    if (!result.startsWith("Ошибка") && !result.startsWith("Error")) {
                                        SkinManager.invalidate(session.playerName)
                                        loadSkins()
                                    }
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth(), primary = false)
                    }
                }
            }
        }
    }
}

private fun pickImage(title: String): File? {
    val dialog = FileDialog(null as JFrame?, title, FileDialog.LOAD)
    dialog.file = "*.png"
    dialog.isVisible = true
    return if (dialog.directory != null && dialog.file != null) File(dialog.directory, dialog.file) else null
}
