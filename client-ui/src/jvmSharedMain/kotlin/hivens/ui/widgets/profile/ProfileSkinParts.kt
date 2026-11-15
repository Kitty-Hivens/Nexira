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
import hivens.accounts.api.AccountKeys
import hivens.accounts.api.SkinUpload
import hivens.accounts.api.SkinUploadStatus
import hivens.ui.accounts.outcomeOf
import hivens.ui.i18n.nexiraStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.identity.SkinManager
import hivens.ui.skin3d.SkinFraming
import hivens.ui.skin3d.SkinView3D
import hivens.ui.skin3d.SkinViewState
import hivens.ui.skin3d.rememberSkinViewState
import hivens.ui.utils.pickFile
import hivens.ui.utils.rememberFileDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.path
import java.io.File
import java.util.UUID
import hivens.widget.api.LocalScope
import hivens.widget.api.rememberScopedCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.theme.Status

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
    framing: SkinFraming = SkinFraming.Full,
    cape: ImageBitmap? = null,
    state: SkinViewState = rememberSkinViewState(),
) {
    val skinManager: SkinManager = koinInject()
    var skin by remember(playerName) { mutableStateOf<ImageBitmap?>(null) }
    // Null alone cannot say which of the two states this is. It meant both "still
    // fetching" and "there is nothing to fetch", so a player with no skin on file,
    // or no network, got a spinner that never stopped.
    var resolved by remember(playerName) { mutableStateOf(false) }

    LaunchedEffect(playerName, refreshKey) {
        resolved = false
        // finally: a decode that throws -- a corrupt cached PNG reaches
        // toComposeImageBitmap -- would otherwise leave the spinner running for
        // good, which is the state this flag was added to end.
        try {
            skin = skinManager.getRawSkin(playerName)
        } finally {
            resolved = true
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        val current = skin
        if (current != null) {
            SkinView3D(
                current,
                Modifier.fillMaxSize(),
                interactive = interactive,
                autoSpin = autoSpin,
                framing = framing,
                cape = cape,
                state = state,
            )
        } else if (!resolved) {
            CircularProgressIndicator(
                color = NxColor.lead(),
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp),
            )
        } else {
            Symbol(NxIcon.Person, null, tint = NxInk.quiet, size = 48.dp)
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

/**
 * The uploader for the account of [providerKey], whose player is [playerName]. The
 * upload is the accounts module's, signed with the account's own session, or with the
 * session the launcher is signed in as where [asSignedIn] says so, which this never
 * holds either way.
 */
@Composable
fun rememberSkinUploader(
    providerKey: String,
    playerName: String,
    asSignedIn: Boolean = false,
    onSkinChanged: () -> Unit,
): SkinUploader {
    val s = nexiraStrings()
    val skinManager: SkinManager = koinInject()
    val scope = rememberCoroutineScope()
    // The picker is UI and belongs to the composition. The upload that follows it
    // does not stop because the reader left the screen mid-upload, and neither does
    // the wait for how it ended.
    val uploadScope: CoroutineScope = koinInject()
    val upload = rememberScopedCommand(AccountKeys.UploadSkin)
    val level = LocalScope.current
    var status by remember { mutableStateOf<UploadStatus>(UploadStatus.None) }
    val dialogSettings = rememberFileDialogSettings(s.text("profileUploadSkin"))

    val pick: () -> Unit = pick@{
        // One upload at a time, as the diagnostic bundle's button holds one write:
        // a second file picked while the first was still uploading raced it, and the
        // skin that stayed was whichever POST landed last.
        if (status == UploadStatus.Loading) return@pick
        scope.launch {
            val picked = pickFile(
                type     = FileKitType.File(extensions = listOf("png")),
                settings = dialogSettings,
            )
            val file = picked?.path?.let { File(it) }
            val uploads = level?.find(AccountKeys.SkinUploads)?.state
            if (file != null && status != UploadStatus.Loading && upload != null && uploads != null) {
                status = UploadStatus.Loading
                val ticket = UUID.randomUUID().toString()
                upload(SkinUpload(providerKey, file.path, cape = false, ticket = ticket, asSignedIn = asSignedIn))
                uploadScope.launch {
                    status = when (val outcome = uploads.outcomeOf(providerKey, ticket)) {
                        SkinUploadStatus.Done -> {
                            skinManager.invalidate(playerName)
                            onSkinChanged()
                            UploadStatus.Success(s.text("profileUploadSuccess"))
                        }
                        is SkinUploadStatus.Failed -> UploadStatus.Error(s.text("profileUploadError", outcome.message ?: s.text("loginErrorGeneric")))
                        SkinUploadStatus.Uploading -> UploadStatus.Loading
                    }
                }
            }
        }
    }
    val refresh: () -> Unit = {
        skinManager.invalidate(playerName)
        onSkinChanged()
    }
    return SkinUploader(status, pick, refresh)
}

/**
 * A change-skin + refresh control block with the upload status above it, for the
 * account of [providerKey], or the session the launcher is signed in as with [asSignedIn].
 */
@Composable
fun SkinControls(
    providerKey: String,
    playerName: String,
    onSkinChanged: () -> Unit,
    modifier: Modifier = Modifier,
    asSignedIn: Boolean = false,
) {
    val s = nexiraStrings()
    val uploader = rememberSkinUploader(providerKey, playerName, asSignedIn, onSkinChanged)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SkinUploadStatusLine(uploader.status)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = uploader.pick) { Text(s.text("profileUploadSkin")) }
            IconButton(onClick = uploader.refresh) {
                Symbol(NxIcon.Refresh, s.text("profileRefresh"), tint = NxInk.quiet)
            }
        }
    }
}

@Composable
fun SkinUploadStatusLine(status: UploadStatus) {
    val s = nexiraStrings()
    when (status) {
        is UploadStatus.Error -> StatusLine(status.message, NxColor.status(Status.Error))
        UploadStatus.Loading  -> StatusLine(s.text("profileUploadSkinLoading"), NxInk.quiet)
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
