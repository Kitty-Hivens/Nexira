package hivens.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import hivens.core.data.SessionData
import hivens.ui.components.CelestiaButton
import hivens.ui.components.GlassCard
import hivens.ui.theme.CelestiaTheme
import hivens.ui.utils.SkinManager
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.io.File
import javax.swing.JFrame

@Composable
fun ProfileScreen(session: SessionData) {
    var frontSkin by remember { mutableStateOf<ImageBitmap?>(null) }
    var backSkin by remember { mutableStateOf<ImageBitmap?>(null) }

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
            GlassCard(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (frontSkin != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                                Image(painter = BitmapPainter(frontSkin!!), contentDescription = "Front", modifier = Modifier.height(300.dp).width(150.dp))
                                if (backSkin != null) {
                                    Image(painter = BitmapPainter(backSkin!!), contentDescription = "Back", modifier = Modifier.height(300.dp).width(150.dp))
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

            Column(Modifier.width(300.dp).fillMaxHeight()) {
                GlassCard(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.padding(24.dp)) {
                        Text(session.playerName, style = MaterialTheme.typography.h5, color = CelestiaTheme.colors.textPrimary)
                        val status = if (session.accessToken.length > 10) "Авторизован" else "Оффлайн"
                        Text("Статус: $status", color = if (session.accessToken.length > 10) CelestiaTheme.colors.success else CelestiaTheme.colors.error)

                        Spacer(Modifier.height(32.dp))

                        CelestiaButton("Загрузить скин", onClick = {
                            val file = pickImage("Выберите скин (PNG)")
                            if (file != null) println("Uploading skin: ${file.name}")
                        }, modifier = Modifier.fillMaxWidth())

                        Spacer(Modifier.height(16.dp))

                        CelestiaButton("Загрузить плащ", onClick = {
                            val file = pickImage("Выберите плащ (PNG)")
                            if (file != null) println("Uploading cloak")
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
