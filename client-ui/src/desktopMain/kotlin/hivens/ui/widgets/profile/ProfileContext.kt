package hivens.ui.widgets.profile

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.core.data.SessionData

// Surface-scoped state the profile widgets share. nav writes
// `selectedCategory.value` on tap; the surface composable reads it
// to pick which content slot to render. session is read by skin /
// account widgets for player name + balance + token-sniff.
// SkinRepository + SkinManager live in Koin and the skin widget
// pulls them via koinInject directly -- kept out of the context to
// match the precedent set by HomeNewContext / LibraryContext, where
// services live per-widget and the context only carries navigation
// + per-surface state. Plain class, not data class -- holds a
// MutableState reference, so generated equals / hashCode would lie
// about value semantics.
class ProfileContext(
    val session: SessionData,
    val selectedCategory: MutableState<ProfileCategory>,
)

val LocalProfileContext: ProvidableCompositionLocal<ProfileContext> =
    staticCompositionLocalOf {
        error("LocalProfileContext not provided -- render inside ProfileSurface")
    }

internal val STUB_PROFILE: ProfileContext = ProfileContext(
    session          = SessionData(),
    selectedCategory = mutableStateOf(ProfileCategory.Skin),
)
