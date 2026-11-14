package hivens.ui.layers

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusEnterExitScope
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The root of a window: draws [content], the window's own, and above it every
 * [Layer] shown from anywhere inside, in the order [WindowLayers] keeps.
 *
 * Each layer draws at full window size in its own place in that order, with the
 * composition locals of the place it was opened from, so a popup opened deep in a
 * screen keeps that screen's theme, strings and scope while it draws over the rails.
 * A layer takes input only where its content has something to take it, and the
 * rest falls through to what is below. A focusable layer takes the keyboard focus
 * from the window's content or from the layer that had it, and gives it back there
 * when it goes (see [Layer]).
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
 * Escape in a focused text field of the window's own content takes the keyboard out
 * of the field instead of going on to back. A field in a layer leaves Escape to close
 * the layer, as a field in a Compose popup or dialog does. The window's key hooks
 * still have to call [DismissStack.onPreviewKeyEvent] and [DismissStack.onKeyEvent],
 * which keep a held key from closing one thing per repeat.
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
    val redirect = rememberCoroutineScope()
    InterceptPlatformTextInput(fields) {
        CompositionLocalProvider(
            LocalWindowLayersProvider provides layers,
            LocalResidentProvider provides null,
        ) {
            // The window's content keeps the constraints the host was given, as the
            // box it replaces did. The layers take the host's size.
            Box(modifier.onKeyEvent(stack::onHostKeyEvent), propagateMinConstraints = true) {
                // A group, so the focus a layer takes from the content can be given back
                // to the part of it that had it. Hidden from accessibility while a modal
                // holds the window, as it is from the pointer.
                Box(
                    Modifier
                        .focusRequester(layers.contentFocus)
                        // Tab does not walk into what a modal blocks: it lands in the modal.
                        .focusProperties { onEnter = { if (layers.plan.gate >= 0) intoModal(layers, redirect) } }
                        .focusGroup()
                        .semantics { if (layers.plan.gate >= 0) hideFromAccessibility() },
                    content = content,
                )
                Residents(layers, owner, redirect)
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
private fun BoxScope.Residents(layers: WindowLayers, owner: OwnerLocals, redirect: CoroutineScope) {
    val plan = layers.plan
    plan.order.forEachIndexed { rank, resident ->
        if (rank == plan.gate) key(InputGateElement) { InputGate() }
        key(resident) {
            if (resident == plan.modal) resident.onOutsidePress?.let { OutsidePress(it) }
            ResidentFrame(resident, owner, layers, redirect)
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BoxScope.ResidentFrame(
    resident: LayerResident,
    owner: OwnerLocals,
    layers: WindowLayers,
    redirect: CoroutineScope,
) {
    val content = resident.content ?: return
    val context = resident.context ?: return
    val focusable = resident.focusable
    if (focusable) TakeFocus(resident, layers)
    val fields = remember(layers) { LayerFieldWatch(layers.dismiss) }
    Box(
        Modifier
            .matchParentSize()
            // A layer under the gate is out of reach of a screen reader too.
            .semantics { if (!layers.plan.takesInput(layers.plan.rankOf(resident))) hideFromAccessibility() }
            // Ends at the top of the software keyboard, see [Layer].
            .then(if (resident.liftsOverKeyboard) Modifier.imePadding() else Modifier)
            .then(if (focusable) Modifier.layerFocus(resident, layers, redirect) else Modifier),
    ) {
        CompositionLocalProvider(context) {
            // Inside the call site's locals, which carry the interceptors of the place
            // the layer was opened from and would otherwise stand in for this one.
            InterceptPlatformTextInput(fields) {
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
}

/**
 * Moves the keyboard focus into a focusable layer as it appears, noting first where
 * it came from so [WindowLayers.hide] can give it back.
 *
 * The note is taken before the layer's content runs its own effects, one of which may
 * already ask for the focus deeper inside: a list that walks under the arrow keys, a
 * field the caret has to be in. When nothing in it has, the focus is let go, as a
 * dialog opens with nothing focused, so keys never reach what the layer blocks, and
 * the first Tab lands inside the layer. Either way the layer gives the focus back
 * when it goes.
 *
 * A layer that yields to a field leaves the keyboard in a text field that holds it
 * as the layer appears. It is the one exception, asked for by a list hung off the
 * field it filters.
 */
@Composable
private fun TakeFocus(resident: LayerResident, layers: WindowLayers) {
    val focusManager = LocalFocusManager.current
    // Decided once, as the layer first appears: a field focused later is the layer's own.
    val yields = remember(resident) { resident.yieldsToField && layers.dismiss.fieldFocused }
    DisposableEffect(resident) {
        if (!resident.shown) return@DisposableEffect onDispose { }
        resident.trigger = layers.triggerHoldingFocus()
        resident.takenFrom = layers.layerHoldingFocus(except = resident)
        val from = layers.focusHolder(except = resident)
        val saved = from.saveFocusedChild()
        // The window's content as a whole cannot take the focus, a layer's root can:
        // with no child of either noted, only a layer is worth handing it back to.
        resident.focusReturn = from.takeIf { saved || it !== layers.contentFocus }
        resident.focusReturnToChild = saved
        onDispose { }
    }
    if (yields) return
    LaunchedEffect(resident) {
        if (resident.shown && !resident.holdsFocus) {
            resident.tookFocus = true
            focusManager.clearFocus()
        }
    }
}

/**
 * The focus group at a focusable layer's root. It never holds the focus itself, so
 * no press of Tab lands on something nobody can see.
 *
 * A modal layer keeps the focus inside while it is the topmost modal or one of its
 * ancestors: Tab, Shift and Tab and the arrow keys stop at its edge instead of
 * walking into what the layer blocks. Focus asked for from code goes where it is
 * asked, which is how a layer opened from this one takes it, and a layer on its way
 * out lets the focus go back. A layer a modal blocks is not walked into either.
 */
private fun Modifier.layerFocus(resident: LayerResident, layers: WindowLayers, redirect: CoroutineScope): Modifier = this
    .onFocusChanged { resident.holdsFocus = it.hasFocus }
    .focusRequester(resident.focus)
    .focusProperties {
        canFocus = false
        onEnter = {
            val plan = layers.plan
            if (!plan.takesInput(plan.rankOf(resident))) intoModal(layers, redirect)
        }
        onExit = {
            if (resident.modal && !resident.releasing && layers.holdsModal(resident)) {
                if (requestedFocusDirection.isTraversal()) cancelFocusChange()
            }
        }
    }
    .focusTarget()

/**
 * Turns a step of Tab or an arrow key into something a modal blocks back into the
 * modal itself, a frame later once the refused step has ended. Focus asked for from
 * code is let through.
 */
private fun FocusEnterExitScope.intoModal(layers: WindowLayers, redirect: CoroutineScope) {
    if (!requestedFocusDirection.isTraversal()) return
    cancelFocusChange()
    redirect.launch { layers.plan.modal?.focus?.requestFocus(FocusDirection.Enter) }
}

/** Whether the focus is moving by Tab or an arrow key, rather than being asked for. */
private fun FocusDirection.isTraversal(): Boolean =
    this == FocusDirection.Next || this == FocusDirection.Previous || this == FocusDirection.Up ||
        this == FocusDirection.Down || this == FocusDirection.Left || this == FocusDirection.Right

@Composable
private fun BoxScope.InputGate() {
    Box(Modifier.matchParentSize().then(InputGateElement))
}

@Composable
internal fun BoxScope.OutsidePress(onPress: () -> Unit) {
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
 * Whether this press came from a finger or from a mouse's primary, secondary or
 * middle button: a press that can mean "outside", not one of the buttons the window
 * turns into back and forward.
 */
internal expect fun PointerEvent.pressesMainButton(): Boolean

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
        // The mouse's back and forward buttons are back and forward, which the window
        // routes through its dismiss stack. Taken here too, one press closed two things.
        if (!pointerEvent.pressesMainButton()) return
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

/**
 * Notes the sessions opened inside one layer, which [FieldWatch] above it counts as
 * well. The difference is the window's own content, the only place whose field the
 * host takes Escape from.
 */
@OptIn(ExperimentalComposeUiApi::class)
private class LayerFieldWatch(private val stack: DismissStack) : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing {
        stack.layerFieldOpened()
        try {
            nextHandler.startInputMethod(request)
        } finally {
            stack.layerFieldClosed()
        }
    }
}
