package hivens.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
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
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.io.File
import java.net.URI
import javax.swing.JFrame

/** Tri-state for the skin upload status so colour is not determined by string content. */
private sealed class UploadStatus {
    object None    : UploadStatus()
    object Loading : UploadStatus()
    data class Success(val message: String) : UploadStatus()
    data class Error(val message: String)   : UploadStatus()
}

@Composable
fun ProfileScreen(session: SessionData, skinRepository: SkinRepository) {
    val s            = LocalStrings.current
    var frontSkin    by remember { mutableStateOf<ImageBitmap?>(null) }
    var backSkin     by remember { mutableStateOf<ImageBitmap?>(null) }
    var uploadStatus by remember { mutableStateOf<UploadStatus>(UploadStatus.None) }

    val scope        = rememberCoroutineScope()

    fun loadSkins() {
        scope.launch {
            frontSkin = SkinManager.getSkinFront(session.playerName)
            backSkin  = SkinManager.getSkinBack(session.playerName)
        }
    }

    LaunchedEffect(Unit) { loadSkins() }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(s.profileTitle, style = MaterialTheme.typography.displaySmall, color = CelestiaTheme.colors.textPrimary)
        Spacer(Modifier.height(24.dp))

        Row(Modifier.fillMaxSize()) {
            // ── Skin preview ──────────────────────────────────────────────────
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
                                    painter            = BitmapPainter(frontSkin!!),
                                    contentDescription = s.profileSkinFront,
                                    modifier           = Modifier.height(300.dp).width(150.dp)
                                )
                                if (backSkin != null) {
                                    Image(
                                        painter            = BitmapPainter(backSkin!!),
                                        contentDescription = s.profileSkinBack,
                                        modifier           = Modifier.height(300.dp).width(150.dp)
                                    )
                                }
                            }
                        } else {
                            Text(s.profileSkinLoading, color = CelestiaTheme.colors.textSecondary)
                        }
                    }
                    IconButton(
                        onClick  = { SkinManager.invalidate(session.playerName); loadSkins() },
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    ) {
                        Icon(Icons.Default.Refresh, s.profileRefresh, tint = CelestiaTheme.colors.textPrimary)
                    }
                }
            }

            Spacer(Modifier.width(24.dp))

            // ── Info & actions ────────────────────────────────────────────────
            Column(Modifier.width(320.dp).fillMaxHeight()) {
                GlassCard(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.padding(24.dp)) {
                        Text(
                            session.playerName,
                            style = MaterialTheme.typography.headlineSmall,
                            color = CelestiaTheme.colors.textPrimary
                        )

                        val isOnline = session.accessToken.length > 10
                        Text(
                            "${s.profileStatusLabel}: " +
                                    if (isOnline) s.profileStatusOnline else s.profileStatusOffline,
                            color = if (isOnline) CelestiaTheme.colors.success
                            else CelestiaTheme.colors.error
                        )

                        Spacer(Modifier.height(24.dp))

                        // Balance card
                        GlassCard(
                            modifier        = Modifier.fillMaxWidth(),
                            backgroundColor = CelestiaTheme.colors.background.copy(alpha = 0.4f),
                            shape           = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier              = Modifier.padding(16.dp),
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Star, s.profileBalance, tint = Color(0xFFFFD700), modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(s.profileBalance, color = CelestiaTheme.colors.textSecondary)
                                }
                                Text(
                                    text       = "${session.balance} ⛃",
                                    style      = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color      = CelestiaTheme.colors.textPrimary
                                )
                            }
                        }

                        Spacer(Modifier.height(32.dp))

                        // Upload status
                        when (val status = uploadStatus) {
                            is UploadStatus.Success -> Text(
                                status.message,
                                color    = CelestiaTheme.colors.success,
                                style    = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            is UploadStatus.Error -> Text(
                                status.message,
                                color    = CelestiaTheme.colors.error,
                                style    = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            is UploadStatus.Loading -> Text(
                                s.profileUploadSkinLoading,
                                color    = CelestiaTheme.colors.textSecondary,
                                style    = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            else -> Unit
                        }

                        CelestiaButton(
                            s.profileTopUp,
                            onClick  = {
                                runCatching {
                                    val url = "http://smartycraft.ru/cabinet"
                                    if (Desktop.isDesktopSupported() &&
                                        Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                                        Desktop.getDesktop().browse(URI(url))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(16.dp))

                        CelestiaButton(
                            text     = s.profileUploadSkin,
                            onClick  = {
                                scope.launch {
                                    val result = FileKit.openFilePicker(
                                        type = FileKitType.File(extensions = listOf("png")),
                                        dialogSettings = FileKitDialogSettings(title = s.profileUploadSkin)
                                    )
                                    val file = result?.path?.let { File(it) }
                                    if (file != null) {
                                        uploadStatus = UploadStatus.Loading
                                        try {
                                            val uploadResult = withContext(Dispatchers.IO) {
                                                skinRepository.uploadSkin(file, false, session)
                                            }
                                            // FIX: Check the result instead of assuming success.
                                            // SkinRepository returns "OK" on success, error string otherwise.
                                            if (uploadResult == "OK") {
                                                uploadStatus = UploadStatus.Success(s.profileUploadSuccess)
                                                SkinManager.invalidate(session.playerName)
                                                loadSkins()
                                            } else {
                                                uploadStatus = UploadStatus.Error(s.profileUploadError(uploadResult))
                                            }
                                        } catch (e: Exception) {
                                            uploadStatus = UploadStatus.Error(
                                                s.profileUploadError(e.message ?: s.loginErrorGeneric)
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            primary  = false
                        )
                    }
                }
            }
        }
    }
}
