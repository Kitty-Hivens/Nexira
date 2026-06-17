package hivens.ui.widgets.shell

import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon

// Outlined twins of the rail's filled nav icons, isolated in their own file:
// the outlined and filled extension properties share simple names (Home, Star,
// ...), so importing both in NavEntryWidget would clash. Used for the
// unselected-entry icon swap (NavSelectionStyle customization). Service entries
// (console / logout) have no twin -- they stay filled.
internal object NavOutlinedIcons {
    val home: IconKey     = NxIcon.Home
    val library: IconKey  = NxIcon.Star
    val browse: IconKey   = NxIcon.Search
    val profile: IconKey  = NxIcon.Person
    val settings: IconKey = NxIcon.Settings
    val about: IconKey    = NxIcon.Info
}
