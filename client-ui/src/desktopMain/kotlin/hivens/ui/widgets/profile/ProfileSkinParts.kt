package hivens.ui.widgets.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import hivens.core.api.SkinRepository
import hivens.core.data.SessionData
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.identity.SkinManager
import hivens.ui.skin3d.SkinView3D
import hivens.ui.theme.NxTheme
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

// Shared skin pieces for the profile surface: the live 3D view ([SkinHero]),
// the upload/refresh logic ([rememberSkinUploader]) and its block + status
// renderers. Reused by the Account tab (skin-forward hero) and the dormant
// skin-studio widget, so rendering and upload live in exactly one place.

/**
 * Live 3D skin for [playerName]. Loads the raw texture off the IO dispatcher
 * and re-loads when [refreshKey] changes (the uploader bumps it after an upload
 * or a manual refresh). Shows a spinner until the first texture arrives.
 */
@Composable
fun SkinHero(
    playerName: String,
    refreshKey: Int,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    autoSpin: Boolean = true,
) {
    val skinManager: SkinManager = koinInject()
    var skin by remember(playerName) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(playerName, refreshKey) {
        skin = skinManager.getRawSkin(playerName)
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        val current = skin
        if (current != null) {
            SkinView3D(current, Modifier.fillMaxSize(), interactive = interactive, autoSpin = autoSpin)
        } else {
            CircularProgressIndicator(
                color = NxTheme.colors.primary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** Pick-a-PNG-and-upload plus refresh, with a transient [status]. Invalidates the
 *  cache and calls onSkinChanged so a sibling [SkinHero] re-loads. */
class SkinUploader internal constructor(
    val status: UploadStatus,
    val pick: () -> Unit,
    val refresh: () -> Unit,
)

@Composable
fun rememberSkinUploader(session: SessionData, onSkinChanged: () -> Unit): SkinUploader {
    val s = LocalStrings.current
    val skinManager: SkinManager = koinInject()
    val skinRepository: SkinRepository = koinInject()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<UploadStatus>(UploadStatus.None) }

    val pick: () -> Unit = {
        scope.launch {
            val picked = FileKit.openFilePicker(
                type = FileKitType.File(extensions = listOf("png")),
                dialogSettings = FileKitDialogSettings(title = s.profileUploadSkin),
            )
            val file = picked?.path?.let { File(it) }
            if (file != null) {
                status = UploadStatus.Loading
                status = try {
                    val result = withContext(Dispatchers.IO) { skinRepository.uploadSkin(file, false, session) }
                    if (result == "OK") {
                        skinManager.invalidate(session.playerName)
                        onSkinChanged()
                        UploadStatus.Success(s.profileUploadSuccess)
                    } else {
                        UploadStatus.Error(s.profileUploadError(result))
                    }
                } catch (e: Exception) {
                    UploadStatus.Error(s.profileUploadError(e.message ?: s.loginErrorGeneric))
                }
            }
        }
    }
    val refresh: () -> Unit = {
        skinManager.invalidate(session.playerName)
        onSkinChanged()
    }
    return SkinUploader(status, pick, refresh)
}

/** A change-skin + refresh control block with the upload status above it. */
@Composable
fun SkinControls(session: SessionData, onSkinChanged: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val uploader = rememberSkinUploader(session, onSkinChanged)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SkinUploadStatusLine(uploader.status)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = uploader.pick) { Text(s.profileUploadSkin) }
            IconButton(onClick = uploader.refresh) {
                Symbol(NxIcon.Refresh, s.profileRefresh, tint = NxTheme.colors.textSecondary)
            }
        }
    }
}

@Composable
fun SkinUploadStatusLine(status: UploadStatus) {
    val s = LocalStrings.current
    when (status) {
        is UploadStatus.Error -> StatusLine(status.message, NxTheme.colors.error)
        UploadStatus.Loading  -> StatusLine(s.profileUploadSkinLoading, NxTheme.colors.textSecondary)
        // Success is silent on purpose -- the changed skin is the feedback.
        is UploadStatus.Success, UploadStatus.None -> Unit
    }
}

@Composable
private fun StatusLine(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
    )
}

sealed class UploadStatus {
    object None : UploadStatus()
    object Loading : UploadStatus()
    data class Success(val message: String) : UploadStatus()
    data class Error(val message: String) : UploadStatus()
}
