package hivens.ui.widgets.profile

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.auth.AuthProviderRegistry
import hivens.core.data.SessionData

// Surface-scoped state the profile widgets share. nav writes
// `selectedCategory.value` on tap; the surface composable reads it
// to pick which content slot to render. session is nullable: null
// means signed out, where only the Sign-in category renders -- the
// skin / account widgets are never mounted without a session. onLogin
// / onLogout thread through to the Sign-in widget so the login form
// (signed out) and logout (signed in) live inside the surface.
// SkinRepository + SkinManager live in Koin and the skin widget pulls
// them via koinInject directly -- kept out of the context to match the
// precedent set by HomeNewContext / LibraryContext. Plain class, not
// data class -- holds a MutableState reference, so generated equals /
// hashCode would lie about value semantics.
class ProfileContext(
    val session: SessionData?,
    val selectedCategory: MutableState<ProfileCategory>,
    val onLogin: (SessionData) -> Unit,
    val onLogout: () -> Unit,
)

val LocalProfileContext: ProvidableCompositionLocal<ProfileContext> =
    staticCompositionLocalOf {
        error("LocalProfileContext not provided -- render inside ProfileSurface")
    }

internal val STUB_PROFILE: ProfileContext = ProfileContext(
    session          = SessionData(),
    selectedCategory = mutableStateOf(ProfileCategory.SmartyCraft),
    onLogin          = {},
    onLogout         = {},
)

// Microsoft / multi-account is deferred: it is gated on a registered device-code
// provider, which only joins the registry when a Microsoft client id is configured.
// Single-sourced here so the profile nav and the sign-in section share one gate.
internal fun AuthProviderRegistry.hasDeviceCodeProvider(): Boolean =
    all.any { it.capabilities.supportsDeviceCode }
