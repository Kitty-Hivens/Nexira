package hivens.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One answer from [rememberReadOffMain]. A wrapper so "not read yet" and "read,
 * and the answer was null" are two different things to the caller.
 */
class Loaded<out T>(val value: T)

/**
 * [read] run on the IO dispatcher, and again whenever [keys] change.
 *
 * Null until the first answer lands. A re-read keeps the previous answer on
 * screen until the new one arrives, so bumping a key does not blank what was
 * already drawn.
 *
 * For a read that can block. The account store is the case it was written for:
 * its reads go to the OS keyring, where one call may wait seconds on a Secret
 * Service that is slow to answer, and composition runs on the window's only UI
 * thread.
 */
@Composable
fun <T> rememberReadOffMain(vararg keys: Any?, read: () -> T): Loaded<T>? {
    var loaded by remember { mutableStateOf<Loaded<T>?>(null) }
    val currentRead by rememberUpdatedState(read)
    LaunchedEffect(*keys) {
        loaded = Loaded(withContext(Dispatchers.IO) { currentRead() })
    }
    return loaded
}
