package hivens.ui.feature.catalogue.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import hivens.i18n.api.LocalTranslator
import hivens.i18n.take
import hivens.ui.i18n.NexiraStrings
import hivens.widget.api.CompiledModule

/**
 * The launcher's shipped words and the words of [modules], in [tag], for a sheet or a
 * test that draws what a source module names: its name and its filters' labels.
 */
@Composable
internal fun ModuleLanguageProvider(tag: String, modules: List<CompiledModule>, content: @Composable () -> Unit) {
    val translator = remember(tag, modules) {
        NexiraStrings.engine(null, tag).apply { modules.forEach { take(it.id, it.strings) } }.current.value
    }
    CompositionLocalProvider(LocalTranslator provides translator, content = content)
}
