package hivens.widget.api

/**
 * Something a module adds to the launcher besides widgets: a screen effect, a theme, a particle
 * field. Every kind of contribution shares this much, which is what lets the loader, the module
 * list and the settings treat them alike without knowing any of them.
 *
 * A module carries its contributions as services, one service file per kind in
 * `META-INF/services`, named after the kind's interface. A kind is an interface that extends this
 * one, and the launcher decides what each kind is for: effects wrap the interface, themes join the
 * theme list, particle fields become widgets. A new kind is one interface here and one consumer in
 * the launcher, and no module that does not use it has to change.
 */
interface ModuleContribution {
    /** Stable across versions of the module, since the person's choices are stored by it. */
    val id: String

    /** What the launcher calls it: a key into the module's [ModuleStrings], shown as written without an entry. */
    val label: String

    /** What the person can adjust, drawn and stored by the launcher. Nothing by default. */
    val settings: List<ModuleSetting> get() = emptyList()

    companion object {
        /** The kinds of contribution this build of the launcher knows. A kind a module declares that is not here is never read. */
        val kinds: List<Class<out ModuleContribution>> = listOf(
            ScreenEffect::class.java,
            ThemeContribution::class.java,
            ParticleContribution::class.java,
        )

        /**
         * Why [items], the contributions of one kind from one module, cannot work as declared, or
         * null when they can. Reads of the module's own code are guarded by [read], which returns the
         * value or the failure that reading it threw.
         */
        fun faultIn(items: List<ModuleContribution>, read: (() -> Any?) -> Result<Any?>): String? {
            val ids = mutableListOf<String>()
            items.forEach { item ->
                val id = read { item.id }.getOrElse { return "a contribution's id could not be read: ${it.message}" } as String
                if (id.isBlank()) return "a contribution has no id"
                if (id != id.trim() || '/' in id) return "contribution id '$id' has surrounding space or a slash"
                ids += id
                val _ = read { item.label }.getOrElse { return "contribution '$id' has no readable label: ${it.message}" }
                @Suppress("UNCHECKED_CAST")
                val settings = read { item.settings }
                    .getOrElse { return "contribution '$id' has settings that could not be read: ${it.message}" } as List<ModuleSetting>
                ModuleSetting.faultIn(settings)?.let { return "contribution '$id': $it" }
            }
            ids.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let { return "two contributions of one kind share the id '${it.key}'" }
            return null
        }
    }
}
