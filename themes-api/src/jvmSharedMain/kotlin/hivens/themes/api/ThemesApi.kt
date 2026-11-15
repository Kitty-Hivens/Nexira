package hivens.themes.api

import hivens.widget.model.ApiVersion

/**
 * The themes module's API: what another module compiles against to read the theme the
 * launcher is drawn in and the themes on offer, to choose a theme and what decides dark
 * and light ([ThemesKeys]), and to bring themes of its own ([ThemeContribution]).
 *
 * A theme is drawn by the design system, nx-ui, which knows nothing of where a choice is
 * kept. This module keeps the library of themes and the choice, and hands the theme to
 * draw to whoever asks.
 *
 * A module built against it names [ID] and the version it was built against in its
 * manifest, and the loader refuses it where this launcher carries a version that does
 * not serve that one (see [ApiVersion.serves]).
 */
object ThemesApi {
    /** What a manifest names this API by, and what a document names the module by to bring it in. */
    const val ID: String = "nexira.themes"

    /**
     * The version of this API. A minor step only adds. A major step removes or changes
     * a key or a value type.
     *
     * The module builds stamp their manifests from the `nexira.themesApi` Gradle property,
     * which a test holds equal to this.
     */
    val VERSION: ApiVersion = ApiVersion(1, 0)

    /**
     * The id of the theme made from the wallpaper's colours. It is offered while there is a
     * wallpaper to make it from, and a choice of it is kept while there is none.
     */
    const val WALLPAPER_THEME_ID: String = "wallpaper"
}
