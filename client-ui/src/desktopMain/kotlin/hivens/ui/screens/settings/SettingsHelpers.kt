package hivens.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import hivens.ui.theme.NxTheme

/**
 * Glass-tinted panel background for the few legacy Settings rows still off an
 * [hivens.ui.nx.NxSection] plane (the editor's surface-properties panel). The Settings
 * page stays neutral; Flat treatment applies only to content surfaces (NxCard,
 * library cards) elsewhere.
 */
@Composable
internal fun settingsRowBackground(): Color =
    NxTheme.colors.background.copy(alpha = 0.4f)
