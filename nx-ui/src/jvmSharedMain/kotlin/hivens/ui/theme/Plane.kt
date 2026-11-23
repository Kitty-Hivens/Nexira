package hivens.ui.theme

import androidx.compose.runtime.ProvidableCompositionLocal

/** What is behind a plane, see [hivens.look.surface.api.Backdrop]. */
typealias Backdrop = hivens.look.surface.api.Backdrop

/** The plane content is drawn on, see [hivens.look.surface.api.Plane]. */
typealias Plane = hivens.look.surface.api.Plane

/** The plane content sits on, see [hivens.look.surface.api.LocalPlane]. Null outside a surface; [NxTheme] provides the page. */
val LocalPlane: ProvidableCompositionLocal<Plane?> = hivens.look.surface.api.LocalPlane
