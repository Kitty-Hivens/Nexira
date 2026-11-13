package hivens.ui.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

@Composable
internal actual fun RouteBackToStack(stack: DismissStack) {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher ?: return
    val handler = remember(stack) { StackBackHandler(stack) }
    // Handlers are asked newest first, so this one, added as the window's content
    // starts, is asked after every popup and dialog the content opens.
    DisposableEffect(dispatcher, handler) {
        dispatcher.addHandler(handler)
        onDispose { handler.remove() }
    }
    LaunchedEffect(handler) {
        snapshotFlow { stack.handlesBack }.collect { handler.isBackEnabled = it }
    }
}

private class StackBackHandler(private val stack: DismissStack) :
    NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, false) {
    override fun onBackCompleted() {
        stack.dismissTop()
    }
}
