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
        Text("ПРОФИЛЬ", style = MaterialTheme.typography.h4, color = CelestiaTheme.colors.textPrimary)
        Spacer(Modifier.height(24.dp))

        Row(Modifier.fillMaxSize()) {
            // Левая часть (Скин)
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
                                    contentDescription = "Front",
                                    modifier = Modifier.height(300.dp).width(150.dp)
                                )
                                if (backSkin != null) {
                                    Image(
                                        painter = BitmapPainter(backSkin!!),
                                        contentDescription = "Back",
                                        modifier = Modifier.height(300.dp).width(150.dp)
                                    )
                                }
                            }
                        } else {
                            Text("Загрузка скина...", color = CelestiaTheme.colors.textSecondary)
                        }
                    }
                    IconButton(
                        onClick = { SkinManager.invalidate(session.playerName); loadSkins() },
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    ) {
                        Icon(Icons.Default.Refresh, "Обновить", tint = CelestiaTheme.colors.textPrimary)
                    }
                }
            }

            Spacer(Modifier.width(24.dp))

            // Правая часть (Инфо и действия)
            Column(Modifier.width(320.dp).fillMaxHeight()) {
                GlassCard(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.padding(24.dp)) {
                        Text(session.playerName, style = MaterialTheme.typography.h5, color = CelestiaTheme.colors.textPrimary)

                        val status = if (session.accessToken.length > 10) "Авторизован" else "Оффлайн"
                        Text(
                            "Статус: $status",
                            color = if (session.accessToken.length > 10) CelestiaTheme.colors.success else CelestiaTheme.colors.error
                        )

                        Spacer(Modifier.height(24.dp))

                        // Баланс
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
                                        contentDescription = "Balance",
                                        tint = Color(0xFFFFD700),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text("Баланс", color = CelestiaTheme.colors.textSecondary)
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

                        // Статус загрузки
                        if (uploadStatus.isNotEmpty()) {
                            Text(
                                text = uploadStatus,
                                color = if (uploadStatus.startsWith("Ошибка")) CelestiaTheme.colors.error else CelestiaTheme.colors.success,
                                style = MaterialTheme.typography.caption,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        // Кнопки
                        CelestiaButton("Пополнить баланс", onClick = {
                            try {
                                val url = "http://smartycraft.ru/cabinet"
                                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                                    Desktop.getDesktop().browse(URI(url))
                                }
                            } catch (e: Exception) { e.printStackTrace() }
                        }, modifier = Modifier.fillMaxWidth())

                        Spacer(Modifier.height(16.dp))

                        CelestiaButton("Загрузить скин", onClick = {
                            val file = pickImage("Выберите скин (PNG)")
                            if (file != null) {
                                uploadStatus = "Загрузка..."
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        // Вызываем новый метод из SkinRepository
                                        skinRepository.uploadSkin(file, false, session)
                                    }
                                    uploadStatus = result
                                    if (!result.startsWith("Ошибка")) {
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
