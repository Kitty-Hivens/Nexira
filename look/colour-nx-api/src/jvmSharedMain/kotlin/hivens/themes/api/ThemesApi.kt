package hivens.themes.api

import hivens.widget.model.ApiVersion

/**
 * The themes module's API: what another module compiles against to read the theme the
 * launcher is drawn in and the themes on offer, to choose a theme and what decides dark
 * and light ([ThemesKeys]), and to bring themes of its own ([ThemeContribution]).
 *
 * The design system, nx-ui, draws from the colour axis's answers and knows no theme. This
 * module keeps the library of themes and the choice, answers the colour axis from the theme
 * in use, and hands the theme to whoever asks.
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
     * 1.1 adds [COLOUR_FAMILY] and [ThemePreview]. It also drops `SystemScheme` and
     * `ThemeInputs.systemScheme`, through which the host told the module the system's colour
     * scheme, since the module reads the scheme itself. That breaks no jar: [ThemeInputs] is a
     * host service, which no jar reaches, and `SystemScheme` was only ever handed to it.
     *
     * 2.0 holds [Theme], [Scheme], [SchemeColours], [colourAnswers] and [WallpaperTone] itself. They were the
     * design system's, which no longer comes along with this API.
     *
     * The module builds stamp their manifests from the `nexira.themesApi` Gradle property,
     * which a test holds equal to this.
     */
    val VERSION: ApiVersion = ApiVersion(2, 0)

    /**
     * The family this module answers the look's colour axis as. What the look's state names while
     * this module draws the colour, so a module that steers it asks whether it is the one drawing.
     */
    const val COLOUR_FAMILY: String = "nx"

    /**
     * The id of the theme made from the wallpaper's colours. It is offered while there is a
     * wallpaper to make it from, and a choice of it is kept while there is none.
     */
    const val WALLPAPER_THEME_ID: String = "wallpaper"
}
