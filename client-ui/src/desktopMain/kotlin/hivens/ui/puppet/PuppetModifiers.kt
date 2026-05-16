package hivens.ui.puppet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

/**
 * Composable side-effects that register interactive widgets with
 * [PuppetRegistry] for the duration of their composition.
 *
 * Pattern:
 *   * Each helper uses [rememberUpdatedState] for every captured
 *     parameter, so the registry always invokes the LATEST lambda /
 *     reads the LATEST value — even when the host Composable
 *     recomposes with new arguments.
 *   * [DisposableEffect] keyed on `id` registers on entry and
 *     unregisters on exit; navigating away from a screen removes its
 *     elements cleanly, no stale entries.
 *
 * Usage at call site:
 * ```kotlin
 * var username by remember { mutableStateOf("") }
 * OutlinedTextField(value = username, onValueChange = { username = it })
 * PuppetField("login.username", username, { username = it })
 *
 * Button(onClick = { doLogin() }) { Text("Log in") }
 * PuppetClick("login.submit") { doLogin() }
 * ```
 *
 * Place the puppet call NEXT TO the widget it shadows, not wrapped
 * around it — keeps the existing UI tree untouched and the puppet
 * declarations skimmable.
 */

@Composable
internal fun PuppetClick(
    id: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val currentClick   = rememberUpdatedState(onClick)
    val currentEnabled = rememberUpdatedState(enabled)
    DisposableEffect(id) {
        PuppetRegistry.registerClick(
            id      = id,
            enabled = { currentEnabled.value },
            onClick = { currentClick.value() },
        )
        onDispose { PuppetRegistry.unregisterClick(id) }
    }
}

// onValueChange is the LAST parameter so callers can use trailing-lambda
// syntax: `PuppetField("id", value) { newValue -> ... }`. Don't reorder.

@Composable
internal fun PuppetField(
    id: String,
    value: String,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    val currentValue   = rememberUpdatedState(value)
    val currentSetter  = rememberUpdatedState(onValueChange)
    val currentEnabled = rememberUpdatedState(enabled)
    DisposableEffect(id) {
        PuppetRegistry.registerField(
            id       = id,
            getValue = { currentValue.value },
            setValue = { currentSetter.value(it) },
            enabled  = { currentEnabled.value },
        )
        onDispose { PuppetRegistry.unregisterField(id) }
    }
}

@Composable
internal fun PuppetToggle(
    id: String,
    value: Boolean,
    enabled: Boolean = true,
    onValueChange: (Boolean) -> Unit,
) {
    val currentValue   = rememberUpdatedState(value)
    val currentSetter  = rememberUpdatedState(onValueChange)
    val currentEnabled = rememberUpdatedState(enabled)
    DisposableEffect(id) {
        PuppetRegistry.registerToggle(
            id       = id,
            getValue = { currentValue.value },
            setValue = { currentSetter.value(it) },
            enabled  = { currentEnabled.value },
        )
        onDispose { PuppetRegistry.unregisterToggle(id) }
    }
}

/**
 * Marks the currently-displayed screen by name. Place at the top of a
 * top-level Composable that represents a navigable surface (Login,
 * Dashboard, Settings, …). The puppet HTTP `/screen` endpoint returns
 * whatever was last set here.
 *
 * Nested calls are NOT scoped (out of MVP scope) — if two PuppetScreen
 * declarations are alive simultaneously, the most recently composed
 * one wins. Aura's current navigation is a single top-level screen at
 * a time, so this is fine.
 */
@Composable
internal fun PuppetScreen(name: String) {
    DisposableEffect(name) {
        PuppetRegistry.setCurrentScreen(name)
        onDispose { /* see KDoc on nested scoping */ }
    }
}
