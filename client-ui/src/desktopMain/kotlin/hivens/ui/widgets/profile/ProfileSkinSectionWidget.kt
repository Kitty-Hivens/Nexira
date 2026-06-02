package hivens.ui.widgets.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import hivens.core.api.SkinRepository
import hivens.ui.easter.LocalAprilFools
import hivens.ui.i18n.LocalStrings
import hivens.ui.identity.SkinManager
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

// Skin preview + upload + refresh. Reads session from
// LocalProfileContext; pulls SkinManager + SkinRepository from Koin
// directly (services live per-widget, not in the surface context).
@Serializable
data class ProfileSkinProps(
    @PropLabel("widget.profile.skin.section.previewHeight") @PropRange(200.0, 480.0) val previewHeight: Int = 360,
)

@Widget(id = "profile.skin.section", displayName = "widget.profile.skin.section", propsClass = ProfileSkinProps::class)
@Composable
fun ProfileSkinSectionWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<ProfileSkinProps>()
    val ctx = LocalProfileContext.current
    val s = LocalStrings.current
    val af = LocalAprilFools.current
    val style = LocalStyle.current
    val skinManager: SkinManager       = koinInject()
    val skinRepository: SkinRepository = koinInject()
    val scope = rememberCoroutineScope()
    val session = ctx.session

    var frontSkin    by remember { mutableStateOf<ImageBitmap?>(null) }
    var backSkin     by remember { mutableStateOf<ImageBitmap?>(null) }
    var uploadStatus by remember { mutableStateOf<UploadStatus>(UploadStatus.None) }

    fun loadSkins() {
        scope.launch {
            frontSkin = skinManager.getSkinFront(session.playerName)
            backSkin  = skinManager.getSkinBack(session.playerName)
        }
    }

    LaunchedEffect(Unit) { loadSkins() }

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(p.previewHeight.dp)
                .clip(RoundedCornerShape(style.cardCorner))
                .background(CelestiaTheme.colors.background.copy(alpha = 0.4f)),
        ) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (frontSkin != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Image(
                            painter            = BitmapPainter(frontSkin!!),
                            contentDescription = s.profileSkinFront,
                            modifier           = Modifier.height(300.dp).width(150.dp),
                        )
                        if (backSkin != null) {
                            Image(
                                painter            = BitmapPainter(backSkin!!),
                                contentDescription = s.profileSkinBack,
                                modifier           = Modifier.height(300.dp).width(150.dp),
                            )
                        }
                    }
                } else {
                    Text(s.profileSkinLoading, color = CelestiaTheme.colors.textSecondary)
                }
            }
            IconButton(
                onClick  = { skinManager.invalidate(session.playerName); loadSkins() },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Default.Refresh, s.profileRefresh, tint = CelestiaTheme.colors.textPrimary)
            }
        }

        Spacer(Modifier.height(8.dp))

        when (val status = uploadStatus) {
            is UploadStatus.Success -> Text(
                status.message,
                color    = CelestiaTheme.colors.success,
                style    = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            is UploadStatus.Error -> Text(
                status.message,
                color    = CelestiaTheme.colors.error,
                style    = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            is UploadStatus.Loading -> Text(
                s.profileUploadSkinLoading,
                color    = CelestiaTheme.colors.textSecondary,
                style    = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            UploadStatus.None -> Unit
        }

        af.ChaosButton(
            id      = "profile_upload_skin_btn",
            text    = s.profileUploadSkin,
            onClick = {
                scope.launch {
                    val result = FileKit.openFilePicker(
                        type           = FileKitType.File(extensions = listOf("png")),
                        dialogSettings = FileKitDialogSettings(title = s.profileUploadSkin),
                    )
                    val file = result?.path?.let { File(it) }
                    if (file != null) {
                        uploadStatus = UploadStatus.Loading
                        try {
                            val uploadResult = withContext(Dispatchers.IO) {
                                skinRepository.uploadSkin(file, false, session)
                            }
                            if (uploadResult == "OK") {
                                uploadStatus = UploadStatus.Success(s.profileUploadSuccess)
                                skinManager.invalidate(session.playerName)
                                loadSkins()
                            } else {
                                uploadStatus = UploadStatus.Error(s.profileUploadError(uploadResult))
                            }
                        } catch (e: Exception) {
                            uploadStatus = UploadStatus.Error(
                                s.profileUploadError(e.message ?: s.loginErrorGeneric),
                            )
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.buttonColors(
                containerColor = CelestiaTheme.colors.background.copy(alpha = 0.4f),
                contentColor   = CelestiaTheme.colors.textPrimary,
            ),
        )
    }
}

private sealed class UploadStatus {
    object None    : UploadStatus()
    object Loading : UploadStatus()
    data class Success(val message: String) : UploadStatus()
    data class Error(val message: String)   : UploadStatus()
}
