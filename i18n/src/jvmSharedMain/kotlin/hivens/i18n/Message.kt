package hivens.i18n

/**
 * A message parsed from its text, ready to be written with its arguments.
 *
 * The syntax is the part of ICU MessageFormat the launcher uses, see [hivens.i18n.api.I18nApi]:
 * numbered arguments, `plural` with exact values and `#`, `select`, nesting, and `'{'`, `'}'` and
 * `'#'` as literal characters. An apostrophe anywhere else is itself.
 */
class Message private constructor(private val parts: List<Part>) {

    /** Writes the message with [args], choosing plural forms by [plural]. */
    fun format(args: Array<out Any?>, plural: PluralRule): String =
        StringBuilder().also { write(parts, args, plural, null, it) }.toString()

    /** The argument indices the message reads, at any depth. */
    val arguments: Set<Int> get() = HashSet<Int>().also { collect(parts, it) }

    /**
     * Every text the message can come out as, one per choice of form at each plural and select,
     * with arguments and `#` left empty: what a check of the words themselves reads.
     */
    fun variants(): List<String> = variantsOf(parts)

    internal sealed interface Part {
        data class Literal(val text: String) : Part
        data class Argument(val index: Int) : Part
        data object Hash : Part
        data class Plural(val index: Int, val exact: Map<Long, List<Part>>, val forms: Map<String, List<Part>>) : Part
        data class Select(val index: Int, val forms: Map<String, List<Part>>) : Part
    }

    companion object {
        /** The plural categories a form may be named by. */
        val CATEGORIES: Set<String> = setOf("zero", "one", "two", "few", "many", "other")

        /**
         * How deep plurals and selects may nest. Far past anything a sentence needs, and well short
         * of the stack, so a file written to go deeper is refused as a message rather than taking
         * the thread down with it.
         */
        const val MAX_DEPTH: Int = 32

        /** [text] parsed. Throws [MessageSyntaxException] where it does not follow the syntax. */
        fun parse(text: String): Message = Message(Parser(text).message())

        /** Whether [text] needs parsing at all: one with no brace and no apostrophe is written as it is. */
        fun isPlain(text: String): Boolean = text.none { it == '{' || it == '}' || it == '\'' }

        /** What is wrong with [text] as a message, or null when it parses. */
        fun faultIn(text: String): String? = try {
            parse(text)
            null
        } catch (e: MessageSyntaxException) {
            e.message
        }

        private fun write(parts: List<Part>, args: Array<out Any?>, plural: PluralRule, hash: String?, out: StringBuilder) {
            parts.forEach { part ->
                when (part) {
                    is Part.Literal -> out.append(part.text)
                    is Part.Argument -> if (part.index < args.size) out.append(args[part.index].toString()) else out.append('{').append(part.index).append('}')
                    Part.Hash -> out.append(hash ?: "#")
                    is Part.Plural -> {
                        val arg = args.getOrNull(part.index)
                        val number = numberOf(arg)
                        val form = number?.let { part.exact[it] }
                            ?: part.forms[number?.let(plural::categoryOf) ?: "other"]
                            ?: part.forms.getValue("other")
                        write(form, args, plural, arg?.toString() ?: "{${part.index}}", out)
                    }
                    is Part.Select -> {
                        val form = part.forms[args.getOrNull(part.index)?.toString()] ?: part.forms.getValue("other")
                        write(form, args, plural, hash, out)
                    }
                }
            }
        }

        private fun numberOf(arg: Any?): Long? = when (arg) {
            is Int -> arg.toLong()
            is Long -> arg
            is Short -> arg.toLong()
            is Byte -> arg.toLong()
            is Double -> arg.takeIf { it % 1.0 == 0.0 }?.toLong()
            is Float -> arg.takeIf { it % 1f == 0f }?.toLong()
            is String -> arg.toLongOrNull()
            else -> null
        }

        private fun variantsOf(parts: List<Part>): List<String> = parts.fold(listOf("")) { texts, part ->
            val choices = when (part) {
                is Part.Literal -> listOf(part.text)
                is Part.Argument, Part.Hash -> listOf("")
                is Part.Plural -> (part.exact.values + part.forms.values).flatMap { variantsOf(it) }
                is Part.Select -> part.forms.values.flatMap { variantsOf(it) }
            }
            texts.flatMap { text -> choices.map { text + it } }.distinct()
        }

        private fun collect(parts: List<Part>, into: MutableSet<Int>) {
            parts.forEach { part ->
                when (part) {
                    is Part.Argument -> into += part.index
                    is Part.Plural -> {
                        into += part.index
                        part.exact.values.forEach { collect(it, into) }
                        part.forms.values.forEach { collect(it, into) }
                    }
                    is Part.Select -> {
                        into += part.index
                        part.forms.values.forEach { collect(it, into) }
                    }
                    else -> Unit
                }
            }
        }
    }

    private class Parser(private val text: String) {
        private var at = 0
        private var depth = 0

        fun message(): List<Part> {
            val parts = body(inPlural = false, nested = false)
            if (at < text.length) fail("an unmatched '}'")
            return parts
        }

        // Reads up to the '}' that closes a form, or to the end at the top. The '}' is left for the caller.
        private fun body(inPlural: Boolean, nested: Boolean): List<Part> {
            val parts = ArrayList<Part>()
            val literal = StringBuilder()
            fun flush() {
                if (literal.isNotEmpty()) parts += Part.Literal(literal.toString()).also { literal.clear() }
            }
            while (at < text.length) {
                val c = text[at]
                when {
                    c == '\'' && at + 2 < text.length && text[at + 2] == '\'' && text[at + 1] in "{}#" -> {
                        literal.append(text[at + 1])
                        at += 3
                    }
                    c == '{' -> {
                        flush()
                        at++
                        parts += argument(inPlural)
                    }
                    c == '}' -> {
                        if (!nested) fail("an unmatched '}'")
                        flush()
                        return parts
                    }
                    c == '#' && inPlural -> {
                        flush()
                        parts += Part.Hash
                        at++
                    }
                    else -> {
                        literal.append(c)
                        at++
                    }
                }
            }
            if (nested) fail("a form that is never closed")
            flush()
            return parts
        }

        private fun argument(inPlural: Boolean): Part {
            spaces()
            val start = at
            while (at < text.length && text[at].isDigit()) at++
            if (start == at) fail("an argument that is not a number")
            val index = text.substring(start, at).toIntOrNull() ?: fail("an argument number too large")
            spaces()
            if (peek() == '}') {
                at++
                return Part.Argument(index)
            }
            expect(',')
            spaces()
            val type = word()
            spaces()
            expect(',')
            val part = when (type) {
                "plural" -> plural(index)
                "select" -> select(index, inPlural)
                else -> fail("an argument type '$type', which is neither plural nor select")
            }
            spaces()
            expect('}')
            return part
        }

        private fun plural(index: Int): Part {
            val exact = LinkedHashMap<Long, List<Part>>()
            val forms = LinkedHashMap<String, List<Part>>()
            forms(inPlural = true) { selector, form ->
                if (selector.startsWith("=")) {
                    val value = selector.drop(1).toLongOrNull() ?: fail("an exact value '$selector' that is not a whole number")
                    if (exact.put(value, form) != null) fail("the exact value '$selector' twice")
                } else {
                    if (selector !in CATEGORIES) fail("a plural category '$selector', which is not one of ${CATEGORIES.joinToString()}")
                    if (forms.put(selector, form) != null) fail("the plural category '$selector' twice")
                }
            }
            if ("other" !in forms) fail("a plural with no 'other' form")
            return Part.Plural(index, exact, forms)
        }

        private fun select(index: Int, inPlural: Boolean): Part {
            val forms = LinkedHashMap<String, List<Part>>()
            forms(inPlural) { selector, form ->
                if (forms.put(selector, form) != null) fail("the select value '$selector' twice")
            }
            if ("other" !in forms) fail("a select with no 'other' form")
            return Part.Select(index, forms)
        }

        private inline fun forms(inPlural: Boolean, take: (String, List<Part>) -> Unit) {
            if (++depth > MAX_DEPTH) fail("plurals and selects nested deeper than $MAX_DEPTH")
            var any = false
            while (true) {
                spaces()
                if (peek() == '}' || peek() == null) break
                val selector = selector()
                spaces()
                expect('{')
                val form = body(inPlural, nested = true)
                expect('}')
                take(selector, form)
                any = true
            }
            if (!any) fail("a plural or select with no forms")
            depth--
        }

        private fun selector(): String {
            val start = at
            while (at < text.length && !text[at].isWhitespace() && text[at] != '{' && text[at] != '}') at++
            if (start == at) fail("a form with no name")
            return text.substring(start, at)
        }

        private fun word(): String {
            val start = at
            while (at < text.length && text[at].isLetter()) at++
            return text.substring(start, at)
        }

        private fun spaces() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun peek(): Char? = text.getOrNull(at)

        private fun expect(c: Char) {
            if (peek() != c) fail(if (peek() == null) "the end where '$c' was expected" else "'${peek()}' where '$c' was expected")
            at++
        }

        private fun fail(what: String): Nothing = throw MessageSyntaxException("$what at ${at + 1}")
    }
}

/** A message that does not follow the syntax, and where. */
class MessageSyntaxException(message: String) : IllegalArgumentException(message)
