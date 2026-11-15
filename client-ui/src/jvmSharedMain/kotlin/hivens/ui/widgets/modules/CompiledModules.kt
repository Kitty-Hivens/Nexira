package hivens.ui.widgets.modules

import hivens.accounts.AccountsModule
import hivens.packs.PacksModule
import hivens.widget.api.CompiledModule
import hivens.widget.model.ApiVersion

/**
 * The modules compiled into this build. Carrying one is not using it: the host brings
 * in a module's keys only where the document names it, the same as a jar's.
 */
val CompiledModules: List<CompiledModule> = listOf(PacksModule, AccountsModule)

/** The module APIs [modules] publish, which a jar may have been compiled against. */
fun offeredApis(modules: List<CompiledModule> = CompiledModules): Map<String, ApiVersion> =
    modules.fold(emptyMap()) { all, module -> all + module.provides }
