package hivens.ui.theme

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.ui.text.needsCjkFace
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Typeface
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That the two bundled faces still cover what the app hands them.
 *
 * The UI face's side of this used to be a hand-written range list checked here
 * after the fact; it is generated from the face now, so what is left to check is
 * that the generated table is in step with the font on disk (it drifts the moment
 * someone re-subsets Roboto Flex without rerunning the tool) and that the CJK
 * subset really received the blocks the tool was told to keep. Hangul, the
 * radicals and a quarter of the enclosed forms were each absent on a first run.
 */
class BundledFontCoverageTest {

    private fun face(name: String): Typeface {
        val f = File("src/commonMain/composeResources/font/$name")
        assertTrue(f.isFile, "missing ${f.path}; run tools/fonts/regenerate.py")
        return checkNotNull(FontMgr.default.makeFromFile(f.path, 0)) { "did not load: ${f.path}" }
    }

    private val ui by lazy { face("roboto_flex_regular.ttf") }
    private val cjk by lazy { face("noto_cjk_jp.ttf") }

    private val uiMap by lazy { ui.mappedCodepoints() }
    private val cjkMap by lazy { cjk.mappedCodepoints() }

    /**
     * Asked of the file rather than of [Typeface.getUTF32Glyph], which is the
     * platform font manager's answer and differs between platforms: CoreText
     * reports the UI face as drawing a tab and a byte order mark, so this test
     * failed on macOS alone while the table it checks was right everywhere.
     */
    private fun Set<Int>.covers(cp: Int) = cp in this

    /** Both sides of the disagreement, so a failure is readable without a rerun. */
    private fun describe(cp: Int): String = "U+%04X table=%s ui=%s cjk=%s".format(
        cp,
        needsCjkFace(String(Character.toChars(cp))),
        uiMap.covers(cp),
        cjkMap.covers(cp),
    )

    /**
     * The table answers "would switching help", so the invariant is against BOTH
     * faces. Checking it against the UI face alone is what let a byte order mark,
     * a narrow no-break space and a word joiner count as reasons to leave: the UI
     * face has none of them, and neither does the face they were being sent to.
     */
    @Test
    fun `the generated table agrees with both shipped faces`() {
        val disagreements = (0x0000..0xFFFF)
            .filter { Character.isDefined(it) }
            .filter { cp ->
                needsCjkFace(String(Character.toChars(cp))) != (cjkMap.covers(cp) && !uiMap.covers(cp))
            }
        assertEquals(
            emptyList(), disagreements.take(12).map(::describe),
            "${disagreements.size} codepoints where the generated table and the fonts disagree; " +
                "rerun tools/fonts/regenerate.py",
        )
    }

    /**
     * The ten that started this. Nine of them draw nothing in either face, so
     * switching for one buys a missing glyph AND a 7.8 MB face, and they turn up
     * in real tag data constantly: a byte order mark at the head of a string read
     * off a file most of all.
     */
    @Test
    fun `a character neither face draws is not a reason to leave the UI face`() {
        listOf(
            '\u0009' to "tab",
            '\u000A' to "newline",
            '\u000D' to "carriage return",
            '\u2028' to "line separator",
            '\u2029' to "paragraph separator",
            '\u202F' to "narrow no-break space",
            '\u2060' to "word joiner",
            '\uA789' to "modifier colon, what a tagger writes where a filename cannot hold one",
            '\uFEFF' to "byte order mark",
        ).forEach { (ch, what) ->
            assertTrue(!uiMap.covers(ch.code), "the premise moved: the UI face now draws $what")
            assertTrue(!cjkMap.covers(ch.code), "the premise moved: the CJK face now draws $what")
            assertTrue(
                !needsCjkFace("Sacrifice$ch"),
                "a Latin title carrying $what must stay on the UI face",
            )
        }
        // The one of the ten that IS a reason: the CJK face really draws it.
        assertTrue(cjkMap.covers(0x2011), "the premise moved: the CJK face lost the non-breaking hyphen")
        assertTrue(needsCjkFace("Higurashi\u2011Kai"), "only the CJK face draws a non-breaking hyphen")
    }

    @Test
    fun `every block the subsetter was told to keep is in the CJK face`() {
        val absent = (BUNDLED_BLOCKS.flatMap { it.second } + REQUIRED_SYMBOLS.map { it.code })
            .filter { Character.isDefined(it) }
            .filterNot { cjkMap.covers(it) }
            .filterNot { cp -> KNOWN_SOURCE_GAPS.any { cp in it } }
        assertEquals(
            emptyList(), absent.take(12),
            "${absent.size} codepoints are missing from the CJK face. Either the range is not " +
                "in RANGES in tools/fonts/regenerate.py, or the source lacks them and they " +
                "belong in KNOWN_SOURCE_GAPS with a reason",
        )
    }

    /**
     * The selector call sites depend on, evaluated in a composition because it is
     * one. A render cannot answer this on a machine that has Noto CJK installed:
     * the bundled face and the host's are the same face, so the picture is
     * identical either way and only the choice is observable.
     */
    @Test
    fun `the selector sends only what the UI face cannot draw to the CJK family`() {
        val answers = mutableMapOf<String, Boolean>()
        @OptIn(ExperimentalComposeUiApi::class)
        val scene = ImageComposeScene(width = 8, height = 8, density = Density(1f)) {
            listOf(
                "Sacrifice", "Привет", "Grüße", "Xin chào", "€1",
                "追憶のサクラメント", "Taka feat. めらみぽっぷ", "한국어", "Sacrifice ☆",
            ).forEach { answers[it] = familyForText(it) != null }
        }
        scene.render()
        scene.close()

        val routed = answers.filterValues { it }.keys
        val kept = answers.filterValues { !it }.keys
        assertEquals(
            setOf("追憶のサクラメント", "Taka feat. めらみぽっぷ", "한국어", "Sacrifice ☆"),
            routed.toSet(),
            "the wrong strings were sent to the CJK family",
        )
        assertEquals(
            setOf("Sacrifice", "Привет", "Grüße", "Xin chào", "€1"),
            kept.toSet(),
            "a string the UI face covers must keep whatever the style already said",
        )
    }

    @Test
    fun `the strings that started this are drawn from the bundle`() {
        listOf(
            "追憶のサクラメント", "Taka feat. めらみぽっぷ", "日本語", "欢迎", "한국어", "Sacrifice ☆",
        ).forEach { s ->
            assertTrue(needsCjkFace(s), "\"$s\" should not have stayed on the UI face")
            assertEquals("", s.filterNot { cjkMap.covers(it.code) }, "not covered in \"$s\"")
        }
        listOf("Sacrifice", "Привет", "Grüße", "Xin chào", "Ґуля", "€1").forEach {
            assertTrue(!needsCjkFace(it), "\"$it\" should have stayed on the UI face")
        }
    }

    private companion object {
        /** Mirrors RANGES in tools/fonts/regenerate.py. */
        val BUNDLED_BLOCKS = listOf(
            "kana" to (0x3040..0x30FF),
            "hangul syllables" to (0xAC00..0xD7A3),
            "hangul jamo" to (0x1100..0x11FF),
            "CJK unified" to (0x4E00..0x9FFF),
            "CJK radicals" to (0x2E80..0x2FDF),
            "CJK punctuation" to (0x3000..0x303F),
            "enclosed CJK" to (0x3200..0x33FF),
            "halfwidth and fullwidth" to (0xFF01..0xFF5E),
        )

        /**
         * The symbols a scan of real track metadata turned up, asserted one by
         * one because the blocks they live in are only half filled in the source
         * and claiming a whole block would fail on characters nobody uses. Three
         * more the scan found, the four pointed star, the heavy heart and the
         * multiplication x, are absent from the source face itself.
         */
        const val REQUIRED_SYMBOLS = "☆★♪♡♥△∞Ⅱ"

        /** What the pan-CJK source does not map in its Japanese face. */
        val KNOWN_SOURCE_GAPS = listOf(
            // Unified characters encoded after this build of Noto was cut.
            0x9FF0..0x9FFF,
            // One squared-katakana slot the face leaves empty.
            0x332C..0x332C,
        )
    }
}
