package hivens.look.manager.api

import hivens.widget.api.SettingsChange
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey

/**
 * The keys the look manager answers, all at the app level. The ids are constants so a widget can name
 * them in its needs.
 */
object LookKeys {
    const val SETTINGS_ID = "look.settings"
    const val UPDATE_SETTINGS_ID = "look.settings.update"
    const val PROVIDE_ID = "look.provide"
    const val STATE_ID = "look.state"
    const val ANSWERS_ID = "look.answers"

    /** What the person chose, as stored. */
    val Settings = SourceKey<LookChoice>(SETTINGS_ID, ScopeLevel.App)

    /** Changes what the person chose: `update { it.withPack("material") }`. */
    val UpdateSettings = CommandKey<SettingsChange<LookChoice>>(UPDATE_SETTINGS_ID, ScopeLevel.App)

    /**
     * The answers of every axis as the choice resolves them, as one value a host wraps its composition
     * in. The value stays the same while the answers change, which reach only what reads them. The one
     * key of the look a host reads, with [LookProvision.NONE] where no manager answers.
     */
    val Provide = SourceKey<LookProvision>(PROVIDE_ID, ScopeLevel.App)

    /** Which provider answers each axis and why, and what is on offer. */
    val State = SourceKey<LookState>(STATE_ID, ScopeLevel.App)

    /**
     * Every axis's answers now as one value, for code outside composition, see [LookSnapshot]. The same
     * answers [Provide] provides. A service reads them through [LookView] instead, as services read no source.
     */
    val Answers = SourceKey<LookSnapshot>(ANSWERS_ID, ScopeLevel.App)
}
