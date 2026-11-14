package dev.hivens.nexira

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import hivens.ui.AndroidShell
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init

/** The launcher's one screen: the shared shell, drawn edge to edge. */
class NexiraActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Before the content, so the shell's own back handlers are all asked first.
        holdBackAtRoot(onBackPressedDispatcher)
        // The system file picker is opened through the activity.
        FileKit.init(this)
        setContent { AndroidShell() }
    }
}
