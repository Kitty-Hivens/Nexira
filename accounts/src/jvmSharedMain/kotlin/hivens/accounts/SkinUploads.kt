package hivens.accounts

import hivens.accounts.api.SkinUpload
import hivens.accounts.api.SkinUploadStatus
import hivens.accounts.api.SkinUploadView
import hivens.auth.AccountStore
import hivens.core.data.SessionData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Puts a skin or a cape on an account, signed with that account's own session, or
 * with the session the launcher is signed in as where the upload asks for it, which
 * never leaves this module. What a widget sees is where each upload stands.
 *
 * An upload runs on the app's work, not the widget's, so one that the server took is
 * seen through even when the person left the screen that started it.
 */
class SkinUploads(
    /** Sends the file, a cape or not, signed with the session, and answers what the server said: `OK` when it took it. */
    private val send: suspend (file: File, cape: Boolean, session: SessionData) -> String,
    private val accounts: AccountStore,
    /** The session the launcher is signed in as, tokens and all, or null. */
    private val signedIn: () -> SessionData?,
    private val scope: CoroutineScope,
) {
    private val _uploads = MutableStateFlow<Map<String, SkinUploadView>>(emptyMap())

    /** The latest upload to each account, by provider key. */
    val uploads: StateFlow<Map<String, SkinUploadView>> = _uploads.asStateFlow()

    /**
     * Uploads [request]. One at a time per account: a second one while the first is
     * with the server raced it, and the skin that stayed was whichever landed last.
     */
    fun upload(request: SkinUpload) {
        val provider = request.providerKey
        // Decided by the one write that lands, so a retried write cannot let two in.
        while (true) {
            val all = _uploads.value
            if (all[provider]?.status == SkinUploadStatus.Uploading) return
            if (_uploads.compareAndSet(all, all + (provider to SkinUploadView(request.ticket, SkinUploadStatus.Uploading)))) break
        }
        scope.launch {
            val status = try {
                val result = withContext(Dispatchers.IO) {
                    val session = (if (request.asSignedIn) signedIn() else accounts.accountFor(provider)) ?: return@withContext null
                    send(File(request.path), request.cape, session)
                }
                when (result) {
                    "OK" -> SkinUploadStatus.Done
                    else -> SkinUploadStatus.Failed(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SkinUploadStatus.Failed(e.message)
            }
            _uploads.update { it + (provider to SkinUploadView(request.ticket, status)) }
        }
    }
}
