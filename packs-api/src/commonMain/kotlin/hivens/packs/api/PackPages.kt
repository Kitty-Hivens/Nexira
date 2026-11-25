package hivens.packs.api

import hivens.widget.model.PageValue
import kotlinx.serialization.builtins.serializer

/** What an opening hands a page about a pack. Declared by the packs module, so read as missing where a document names none. */
object PackPages {
    const val PACK_ID: String = "pack.page"

    /** The pack the page is about, by its id. */
    val Pack: PageValue<String> = PageValue(PACK_ID, String.serializer())
}
