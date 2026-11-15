package hivens.ui.accounts

import hivens.accounts.api.SkinUploadStatus
import hivens.accounts.api.SkinUploadView
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * How the upload of [ticket] to the account of [provider] ended, waited for on
 * these uploads. An upload refused because another to the same account was under way
 * reads as failed with no words, once that other one ends.
 *
 * The upload is asked for before this is called, and the asking writes the uploads at
 * once, so what this waits on is never an earlier upload's end mistaken for this one's.
 */
suspend fun StateFlow<Map<String, SkinUploadView>>.outcomeOf(provider: String, ticket: String): SkinUploadStatus {
    val ended = first { all -> all[provider]?.status.let { it != null && it != SkinUploadStatus.Uploading } }
    val view = ended.getValue(provider)
    return if (view.ticket == ticket) view.status else SkinUploadStatus.Failed()
}
