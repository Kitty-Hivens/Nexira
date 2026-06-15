package hivens.ui.widgets.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.ui.graphics.vector.ImageVector

// Outlined twins of the rail's filled nav icons, isolated in their own file:
// the outlined and filled extension properties share simple names (Home, Star,
// ...), so importing both in NavEntryWidget would clash. Used for the
// unselected-entry icon swap (NavSelectionStyle customization). Service entries
// (console / logout) have no twin -- they stay filled.
internal object NavOutlinedIcons {
    val home: ImageVector     = Icons.Outlined.Home
    val library: ImageVector  = Icons.Outlined.Star
    val browse: ImageVector   = Icons.Outlined.Search
    val profile: ImageVector  = Icons.Outlined.Person
    val settings: ImageVector = Icons.Outlined.Settings
    val about: ImageVector    = Icons.Outlined.Info
}
