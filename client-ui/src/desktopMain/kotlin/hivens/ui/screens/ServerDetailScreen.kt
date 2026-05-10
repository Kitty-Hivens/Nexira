package hivens.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
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
import hivens.core.api.model.ServerProfile
import hivens.launcher.platform.PlatformPaths
import hivens.ui.components.GlassCard
import hivens.ui.debug.SkiaTracker
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import javax.imageio.ImageIO

@Composable
fun ServerDetailScreen(
    server: ServerProfile,
    onBack: () -> Unit
) {
    val s = LocalStrings.current
    val paths: PlatformPaths = koinInject()

    val assetsPath = remember(server) {
        paths.clientDir(server.assetDir).toFile()
    }

    var description by remember { mutableStateOf<String?>(null) }
    var bannerImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var isLoading   by remember { mutableStateOf(true) }

    LaunchedEffect(server) {
        withContext(Dispatchers.IO) {
            val descFile = File(assetsPath, "description.txt")
            if (descFile.exists()) description = descFile.readText()

            val imgFile = File(assetsPath, "banner.png")
            if (imgFile.exists()) {
                runCatching {
                    bannerImage = ImageIO.read(imgFile)?.toComposeImageBitmap()?.also {
                        SkiaTracker.track("Detail.banner[${server.assetDir}]", it)
                    }
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
                style      = MaterialTheme.typography.titleLarge,
                color      = CelestiaTheme.colors.textSecondary,
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
                            text       = server.title?.uppercase() ?: server.name,
                            style      = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Black,
                            color      = CelestiaTheme.colors.textPrimary
                        )

                        Row(Modifier.padding(vertical = 16.dp)) {
                            ServerTag(server.version)
                            Spacer(Modifier.width(8.dp))
                            ServerTag(server.assetDir)
                        }

                        Spacer(Modifier.height(16.dp))

                        LazyColumn(Modifier.weight(1f)) {
                            item {
                                if (description != null) {
                                    Text(
                                        text       = description!!,
                                        style      = MaterialTheme.typography.bodyLarge,
                                        color      = CelestiaTheme.colors.textPrimary.copy(alpha = 0.8f),
                                        lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.5
                                    )
                                } else {
                                    MissingDataWarning(
                                        title = s.serverDetailMissingTitle,
                                        body  = s.serverDetailMissingPath(assetsPath.absolutePath, "description.txt"),
                                        path  = assetsPath.absolutePath
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
                            .background(CelestiaTheme.colors.surface.copy(alpha = 0.5f))
                            .border(
                                1.dp,
                                CelestiaTheme.colors.outline.copy(alpha = 0.2f),
                                RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bannerImage != null) {
                            Image(
                                painter            = BitmapPainter(bannerImage!!),
                                contentDescription = null,
                                contentScale       = ContentScale.Crop,
                                modifier           = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(s.serverDetailNoImage, color = CelestiaTheme.colors.textSecondary)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    s.serverDetailNoImageHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerTag(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(CelestiaTheme.colors.primary.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text       = text,
            style      = MaterialTheme.typography.bodySmall,
            color      = CelestiaTheme.colors.primary,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun MissingDataWarning(title: String, body: String, path: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFFAA00).copy(alpha = 0.12f))
            .border(1.dp, Color(0xFFFFAA00).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Warning, null, tint = Color(0xFFFFAA00))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color(0xFFFFAA00), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.textPrimary.copy(alpha = 0.7f))
            Text(path, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace)
        }
    }
}
