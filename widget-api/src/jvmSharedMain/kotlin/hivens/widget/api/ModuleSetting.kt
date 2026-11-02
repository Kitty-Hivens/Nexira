package hivens.widget.api

/**
 * One thing a module lets the person adjust, declared by the module and drawn by the launcher.
 *
 * The launcher knows three shapes, which is what keeps a module's settings out of its own code: it
 * lays out a switch, a slider or a row of choices, stores the value under the module's name, and
 * hands the module the answer as [ModuleValues]. A module that wants a new control asks for it here,
 * once, rather than for a hook of its own in the settings screen.
 *
 * [label] and [hint] are keys into the module's [ModuleStrings], shown as written when the module
 * has no entry for them. [key] is stable across versions of the module, since the stored value is
 * found by it.
 */
sealed interface ModuleSetting {
    val key: String
    val label: String
    val hint: String?

    /** A switch. */
    data class Toggle(
        override val key: String,
        override val label: String,
        val default: Boolean = false,
        override val hint: String? = null,
    ) : ModuleSetting

    /**
     * A slider over [range], shown as a percentage of it when [percent] is set and as the number
     * otherwise.
     */
    data class Amount(
        override val key: String,
        override val label: String,
        val default: Float = 0.5f,
        val range: ClosedFloatingPointRange<Float> = 0f..1f,
        val percent: Boolean = true,
        override val hint: String? = null,
    ) : ModuleSetting

    /** One of several named options, shown as chips. */
    data class Choice(
        override val key: String,
        override val label: String,
        val options: List<Option>,
        val default: String = options.firstOrNull()?.id.orEmpty(),
        override val hint: String? = null,
    ) : ModuleSetting {
        data class Option(val id: String, val label: String)
    }

    companion object {
        /** Kept by the launcher for whether a module's effect is on, so a module cannot declare it. */
        const val RESERVED_ENABLED = "enabled"

        /** Why [settings] cannot work as declared, or null when they can. */
        fun faultIn(settings: List<ModuleSetting>): String? {
            val keys = settings.map { it.key.trim() }
            if (keys.any { it.isEmpty() }) return "a setting has no key"
            if (keys.any { it == RESERVED_ENABLED }) return "'$RESERVED_ENABLED' is the launcher's own key and cannot be a setting"
            keys.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let { return "two settings share the key '${it.key}'" }
            settings.forEach { setting ->
                when (setting) {
                    is Toggle -> Unit
                    is Amount -> {
                        if (setting.range.isEmpty() || setting.default !in setting.range) {
                            return "setting '${setting.key}' has a default outside its range"
                        }
                    }
                    is Choice -> {
                        if (setting.options.isEmpty()) return "setting '${setting.key}' offers no options"
                        if (setting.options.map { it.id }.toSet().size != setting.options.size) {
                            return "setting '${setting.key}' repeats an option"
                        }
                        if (setting.options.none { it.id == setting.default }) {
                            return "setting '${setting.key}' has a default that is not one of its options"
                        }
                    }
                }
            }
            return null
        }
    }
}

/**
 * What the person has set, read by a module through the keys it declared.
 *
 * A value that was never set, or that no longer reads (a hand-edited file, an option a newer
 * version of the module dropped), is the declared default, and a slider is held inside its range.
 * Asking for a key the module did not declare is the module's mistake and throws, so it is found at
 * the first frame rather than shown as a silent zero.
 */
class ModuleValues(
    private val settings: List<ModuleSetting>,
    private val stored: Map<String, String> = emptyMap(),
) {
    fun toggle(key: String): Boolean {
        val setting = declared<ModuleSetting.Toggle>(key)
        return stored[key]?.toBooleanStrictOrNull() ?: setting.default
    }

    fun amount(key: String): Float {
        val setting = declared<ModuleSetting.Amount>(key)
        val value = stored[key]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: return setting.default
        return value.coerceIn(setting.range)
    }

    fun choice(key: String): String {
        val setting = declared<ModuleSetting.Choice>(key)
        return stored[key]?.takeIf { value -> setting.options.any { it.id == value } } ?: setting.default
    }

    private inline fun <reified T : ModuleSetting> declared(key: String): T {
        val setting = settings.firstOrNull { it.key == key } ?: error("the module did not declare a setting '$key'")
        return setting as? T ?: error("setting '$key' is a ${setting::class.simpleName}, not a ${T::class.simpleName}")
    }

    companion object {
        val NONE = ModuleValues(emptyList())

        /** The text a value is stored as, which is what a hand-edited file would hold too. */
        fun encode(value: Boolean): String = value.toString()
        fun encode(value: Float): String = value.toString()
    }
}
