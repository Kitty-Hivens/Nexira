package hivens.i18n.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The words a composition is drawn in. The host provides it at the root of every composition,
 * and provides a new one when the language or the words change, which redraws what read it.
 * Without a host every key is shown as itself.
 */
val LocalTranslator: ProvidableCompositionLocal<Translator> = staticCompositionLocalOf { Translator.KEYS }

/** The words of [namespace] in the language this part of the composition is drawn in. */
@Composable
@ReadOnlyComposable
fun strings(namespace: String): Strings = LocalTranslator.current.strings(namespace)

/**
 * Draws [content] in the language [tag], the rest of the composition staying in its own. A tag
 * no language installed answers to leaves [content] in the surrounding language.
 */
@Composable
fun LanguageProvider(tag: String, content: @Composable () -> Unit) {
    val outer = LocalTranslator.current
    val inner = remember(outer, tag) { if (outer.language.tag == tag) outer else outer.inLanguage(tag) }
    CompositionLocalProvider(LocalTranslator provides inner, content = content)
}
