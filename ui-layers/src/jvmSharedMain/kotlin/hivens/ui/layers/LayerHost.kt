package hivens.ui.layers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntSize

/**
 * The root of a window: draws [content], the window's own, and above it every
 * [Layer] shown from anywhere inside, in the order [WindowLayers] keeps.
 *
 * Each layer draws at full window size in its own place in that order, with the
 * composition locals of the place it was opened from, so a popup opened deep in a
 * screen keeps that screen's theme, strings and scope while it draws over the rails.
 * A layer takes input only where its content has something to take it, and the
 * rest falls through to what is below.
 *
 * While a modal is up, an invisible gate under it stops input to everything older,
 * the window's content included, except the layers that stay usable, which are
 * lifted above the gate (see [LayerPlan]). A modal with a dismiss request gets a
 * catcher right under its content: a press that lands outside the modal and that
 * nothing usable took asks it to close.
 *
 * Escape and back: the host registers the [WindowLayers.dismiss] stack as the
 * window's back handler before anything inside it can register one, so a Compose
 * popup or dialog opened later takes back first, the way the platform orders them,
 * and the stack gets what nobody above it took. On Android the shell routes the
 * activity's back to [DismissStack.dismissTop] itself (see [RouteBackToStack]).
 * Escape in a focused text field of the host's own takes the keyboard out of the
 * field instead of going on to back. The window's key hooks still have to call
 * [DismissStack.onPreviewKeyEvent] and [DismissStack.onKeyEvent], which keep a held
 * key from closing one thing per repeat.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LayerHost(
    layers: WindowLayers,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val stack = layers.dismiss
    // Before the content composes, so every back handler inside registers after
    // this one and is asked first.
    RouteBackToStack(stack)
    val focusManager = LocalFocusManager.current
    SideEffect { stack.leaveField = { focusManager.clearFocus() } }
    // A window that loses the focus mid-press never sees the release.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo, stack) {
        snapshotFlow { windowInfo.isWindowFocused }.collect { focused -> if (!focused) stack.resetKeys() }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val textToolbar = LocalTextToolbar.current
    val inputMode = LocalInputModeManager.current
    val clipboard = LocalClipboard.current
    val graphics = LocalGraphicsContext.current
    val accessibility = LocalAccessibilityManager.current
    val haptics = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current
    val owner = remember(
        focusManager, windowInfo, keyboard, textToolbar, inputMode,
        clipboard, graphics, accessibility, haptics, viewConfiguration,
    ) {
        OwnerLocals(
            focusManager, windowInfo, keyboard, textToolbar, inputMode,
            clipboard, graphics, accessibility, haptics, viewConfiguration,
        )
    }
    val fields = remember(layers) { FieldWatch(stack) }
    InterceptPlatformTextInput(fields) {
        CompositionLocalProvider(
            LocalWindowLayersProvider provides layers,
            LocalResidentProvider provides null,
        ) {
            // The window's content keeps the constraints the host was given, as the
            // box it replaces did. The layers take the host's size.
            Box(modifier.onKeyEvent(stack::onHostKeyEvent), propagateMinConstraints = true) {
                Box(content = content)
                Residents(layers, owner)
            }
        }
    }
}

/**
 * The shown layers, in a restart scope of their own that comes after the window's
 * content. A [Layer] in the content writes its flags while composing, and the plan
 * read here is then read later in the same pass rather than a frame behind.
 */
@Composable
private fun BoxScope.Residents(layers: WindowLayers, owner: OwnerLocals) {
    val plan = layers.plan
    plan.order.forEachIndexed { rank, resident ->
        if (rank == plan.gate) key(InputGateElement) { InputGate() }
        key(resident) {
            if (resident == plan.modal) resident.onDismissRequest?.let { OutsidePress(it) }
            ResidentFrame(resident, owner)
        }
    }
}

/**
 * What belongs to the window rather than to the place a layer was opened from. A
 * layer opened inside a Compose popup or an Android dialog captures that layer's
 * owner with everything else, and drawn in the host it has to answer to the host's
 * focus, keyboard, clipboard and window instead.
 */
private class OwnerLocals(
    val focusManager: FocusManager,
    val windowInfo: WindowInfo,
    val keyboard: SoftwareKeyboardController?,
    val textToolbar: TextToolbar,
    val inputMode: InputModeManager,
    val clipboard: Clipboard,
    val graphics: GraphicsContext,
    val accessibility: AccessibilityManager?,
    val haptics: HapticFeedback,
    val viewConfiguration: ViewConfiguration,
)

@Composable
private fun BoxScope.ResidentFrame(resident: LayerResident, owner: OwnerLocals) {
    val content = resident.content ?: return
    val context = resident.context ?: return
    Box(Modifier.matchParentSize()) {
        CompositionLocalProvider(context) {
            CompositionLocalProvider(
                LocalResidentProvider provides resident,
                LocalFocusManager provides owner.focusManager,
                LocalWindowInfo provides owner.windowInfo,
                LocalSoftwareKeyboardController provides owner.keyboard,
                LocalTextToolbar provides owner.textToolbar,
                LocalInputModeManager provides owner.inputMode,
                LocalClipboard provides owner.clipboard,
                LocalGraphicsContext provides owner.graphics,
                LocalAccessibilityManager provides owner.accessibility,
                LocalHapticFeedback provides owner.haptics,
                LocalViewConfiguration provides owner.viewConfiguration,
            ) {
                content()
            }
        }
    }
}

@Composable
private fun BoxScope.InputGate() {
    Box(Modifier.matchParentSize().then(InputGateElement))
}

@Composable
private fun BoxScope.OutsidePress(onPress: () -> Unit) {
    Box(Modifier.matchParentSize().then(OutsidePressElement(onPress)))
}

/**
 * Takes every pointer event that reaches it and passes none on, so nothing under it
 * is hit. It consumes nothing: a catcher above it still sees an untouched press and
 * knows it landed on nothing usable.
 */
private data object InputGateElement : ModifierNodeElement<InputGateNode>() {
    override fun create() = InputGateNode()
    override fun update(node: InputGateNode) = Unit
}

private class InputGateNode : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = Unit
    override fun onCancelPointerInput() = Unit
}

private class OutsidePressElement(val onPress: () -> Unit) : ModifierNodeElement<OutsidePressNode>() {
    override fun create() = OutsidePressNode(onPress)
    override fun update(node: OutsidePressNode) {
        node.onPress = onPress
    }
    override fun equals(other: Any?) = other is OutsidePressElement && other.onPress === onPress
    override fun hashCode() = System.identityHashCode(onPress)
}

/**
 * Asks a modal to close on a press outside it.
 *
 * It shares the press with what is under it, so a layer that stays usable still
 * gets it, and it acts on the last pass, after that layer has had its turn: a press
 * something there consumed was meant for that thing, not for closing the modal.
 */
private class OutsidePressNode(var onPress: () -> Unit) : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Final || pointerEvent.type != PointerEventType.Press) return
        if (pointerEvent.changes.any { it.isConsumed }) return
        onPress()
    }

    override fun onCancelPointerInput() = Unit

    override fun sharePointerInputWithSiblings() = true
}

/**
 * Notes every text input session opened below the host. A session is what a focused
 * editable field holds, on both platforms and in every text field Compose has, so
 * it answers "is a field focused" without a list of field types.
 */
@OptIn(ExperimentalComposeUiApi::class)
private class FieldWatch(private val stack: DismissStack) : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing {
        stack.fieldOpened()
        try {
            nextHandler.startInputMethod(request)
        } finally {
            stack.fieldClosed()
        }
    }
}
