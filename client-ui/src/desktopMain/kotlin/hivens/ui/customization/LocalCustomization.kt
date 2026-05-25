package hivens.ui.customization

import androidx.compose.runtime.compositionLocalOf

/**
 * Read-only access to the active [CustomizationSettings] from any
 * Composable without prop-drilling through every layer. Backed by
 * [CustomizationManager] at the app root.
 *
 * Defaulted to a no-op instance so any caller compiling against
 * the local does not blow up if it is read outside the provider
 * (e.g., previews, isolated tests).
 */
val LocalCustomization = compositionLocalOf { CustomizationSettings() }
