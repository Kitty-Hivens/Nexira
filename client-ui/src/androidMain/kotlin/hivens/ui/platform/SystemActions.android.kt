package hivens.ui.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import java.io.File

// Another app cannot be handed a directory under the application's private storage,
// and a file there needs a content provider to be shared at all.
internal actual fun hostOpenFolder(path: String) = Unit

internal actual fun hostOpenFile(file: File) = Unit

internal actual fun hostOpenUrl(url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        AndroidAppContext.require().startActivity(intent)
    }
}

internal actual fun hostCopyToClipboard(text: String) {
    runCatching {
        val context = AndroidAppContext.require()
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", text))
    }
}
