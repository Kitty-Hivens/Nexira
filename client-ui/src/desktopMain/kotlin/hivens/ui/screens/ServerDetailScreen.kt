package hivens.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.config.AppConfig
import hivens.core.api.model.ServerProfile
import hivens.ui.components.GlassCard
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO

@Composable
fun ServerDetailScreen(
    server: ServerProfile,
    onBack: () -> Unit
) {
    val s = LocalStrings.current

    val assetsPath = remember(server) {
        val userHome = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        val baseDir = when {
            os.contains("win") -> "$userHome/AppData/Roaming/${AppConfig.APP_DIR}"
            os.contains("mac") -> "$userHome/Library/Application Support/${AppConfig.APP_DIR}"
            else -> "$userHome/${AppConfig.APP_DIR}"
        }
        File(baseDir, "clients/${server.assetDir}")
    }

    var description by remember { mutableStateOf<String?>(null) }
    var bannerImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(server) {
        withContext(Dispatchers.IO) {
            val descFile = File(assetsPath, "description.txt")
            if (descFile.exists()) {
                description = descFile.readText()
            }

            val imgFile = File(assetsPath, "banner.png")
            if (imgFile.exists()) {
                try {
                    bannerImage = ImageIO.read(imgFile)?.toComposeImageBitmap()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            isLoading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, s.navBack, tint = CelestiaTheme.colors.textPrimary)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                s.serverDetailTitle,
                style = MaterialTheme.typography.h6,
                color = CelestiaTheme.colors.textSecondary,
                fontWeight = FontWeight.Bold
            )
        }

        GlassCard(Modifier.weight(1f).fillMaxWidth()) {
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CelestiaTheme.colors.primary)
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    // Left: text
                    Column(Modifier.weight(1.5f).padding(32.dp)) {
                        Text(
                            text = server.title?.uppercase() ?: server.name,
                            style = MaterialTheme.typography.h3,
                            fontWeight = FontWeight.Black,
                            color = CelestiaTheme.colors.textPrimary
                        )

                        Row(Modifier.padding(vertical = 16.dp)) {
                            Tag(server.version)
                            Spacer(Modifier.width(8.dp))
                            Tag(server.assetDir)
                        }

                        Spacer(Modifier.height(16.dp))

                        LazyColumn(Modifier.weight(1f)) {
                            item {
                                if (description != null) {
                                    Text(
                                        text = description!!,
                                        style = MaterialTheme.typography.body1,
                                        color = CelestiaTheme.colors.textPrimary.copy(alpha = 0.8f),
                                        lineHeight = MaterialTheme.typography.body1.fontSize * 1.5
                                    )
                                } else {
                                    MissingDataWarning(
                                        title = s.serverDetailMissingTitle,
                                        body = s.serverDetailMissingPath(assetsPath.absolutePath, "description.txt"),
                                        path = assetsPath.absolutePath
                                    )
                                }
                            }
                        }
                    }

                    // Right: image
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(16.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.3f))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bannerImage != null) {
                            Image(
                                painter = BitmapPainter(bannerImage!!),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(s.serverDetailNoImage, color = Color.Gray)
                                Spacer(Modifier.height(8.dp))
                                Text(s.serverDetailNoImageHint, style = MaterialTheme.typography.caption, color = Color.DarkGray)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(CelestiaTheme.colors.primary.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.primary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MissingDataWarning(title: String, body: String, path: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF332200).copy(alpha = 0.5f))
            .border(1.dp, Color(0xFFFFAA00).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Warning, null, tint = Color(0xFFFFAA00))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.subtitle2, color = Color(0xFFFFAA00), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.caption, color = Color.White.copy(alpha = 0.7f))
            Text(path, style = MaterialTheme.typography.caption, color = Color.White.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace)
        }
    }
}
