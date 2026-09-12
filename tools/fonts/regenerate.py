#!/usr/bin/env python3
"""Regenerate the bundled CJK face and the UI face's coverage table.

Writes:
  - nx-ui/src/commonMain/composeResources/font/noto_cjk_jp.ttf   (the CJK subset)
  - nx-ui/src/desktopMain/kotlin/hivens/ui/text/UiFaceCoverage.kt (generated)
  - client-ui/src/commonMain/composeResources/files/OFL-NotoSansCJK.txt

The coverage table is generated rather than written because hivens.ui.text
decides per string whether Roboto Flex can draw it, and getting that list wrong
by one range puts a character in a face that has no glyph for it, which is a box
on screen. Transcribing a font's character table by hand is a standing invitation
to that bug: the first attempt claimed archaic Greek, Ukrainian Ѐ, four dashes
and half the currency block, none of which Roboto Flex carries. Reading it out of
the file cannot drift from the file.

Why a pan-CJK face and not the smaller language-specific Noto Sans JP: the
pan-CJK faces all share one 44810-codepoint character set and differ only in
which glyph a codepoint maps to where regional forms diverge, so this one file
renders Japanese, Chinese in both scripts and Korean without a single missing
glyph. The language-specific release covers the Japanese standards only, and a
Chinese title would come out as boxes, which is worse than the wrong stroke
shapes. The JP face is picked because the launcher's audience is Japanese-heavy;
a second face (SC) drops in the same way when someone needs correct Chinese
forms.

Why Latin, Greek and Cyrillic are kept in it: a family cannot borrow coverage
from a sibling (Compose selects a face by weight and style, never by what it
contains), so whatever family draws a string has to cover the whole string. A
title like "Taka feat. めらみぽっぷ" is therefore rendered entirely by this face,
and consistency inside one string beats consistency with Roboto Flex next to it.

Requires: fontTools (pyftsubset) and the pan-CJK font installed locally
(Arch: noto-fonts-cjk).
"""
import os
import subprocess
import sys

from fontTools.ttLib import TTCollection, TTFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FONT_OUT = os.path.join(ROOT, "nx-ui", "src", "commonMain", "composeResources", "font", "noto_cjk_jp.ttf")
UI_FACE = os.path.join(ROOT, "nx-ui", "src", "commonMain", "composeResources", "font", "roboto_flex_regular.ttf")
COVERAGE_OUT = os.path.join(ROOT, "nx-ui", "src", "desktopMain", "kotlin", "hivens", "ui", "text", "CjkOnlyCoverage.kt")
LICENSE_OUT = os.path.join(ROOT, "client-ui", "src", "commonMain", "composeResources", "files", "OFL-NotoSansCJK.txt")

SOURCE_CANDIDATES = [
    "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    os.path.expanduser("~/.local/share/fonts/NotoSansCJK-Regular.ttc"),
]
LICENSE_CANDIDATES = [
    "/usr/share/licenses/noto-fonts-cjk/LICENSE",
    "/usr/share/doc/fonts-noto-cjk/LICENSE",
]

# Which face inside the collection. Proportional, not the Mono variant.
FACE_SUFFIX = "CJK JP"

# What the face has to cover, and the contract hivens.ui.text.needsCjkFace is
# checked against: whatever that routes here has to be drawable here, or the
# routing produces boxes instead of the host fallback it replaced.
#
# The Latin, Greek and Cyrillic ranges are here so a mixed-script string never
# has to leave this face; see the note above. Unified extension A is left out on
# evidence rather than taste: it costs 1.86 MB and a scan of 7694 real metadata
# fields found zero characters from it, while hangul costs 1.57 MB and 31 of its
# characters were in use.
#
# The symbol blocks are here for the same reason: a scan of the same fields found
# 191 uses of the likes of the star, the eighth note and the white heart, which
# Japanese titles are full of, and all seven blocks together cost 27 KB.
RANGES = [
    (0x0020, 0x007F, "ASCII"),
    (0x00A0, 0x0250, "Latin-1 and Latin extended"),
    (0x0370, 0x0400, "Greek"),
    (0x0400, 0x0500, "Cyrillic"),
    (0x2000, 0x2070, "general punctuation"),
    (0x20A0, 0x20C0, "currency"),
    (0x2100, 0x2150, "letterlike"),
    (0x2150, 0x2190, "number forms"),
    (0x2190, 0x2200, "arrows"),
    (0x2200, 0x2300, "mathematical operators"),
    (0x2300, 0x2400, "miscellaneous technical"),
    (0x2460, 0x2500, "enclosed alphanumerics"),
    (0x25A0, 0x2600, "geometric shapes"),
    (0x2600, 0x2700, "miscellaneous symbols"),
    (0x2700, 0x27C0, "dingbats"),
    (0x2E80, 0x2FE0, "CJK radicals"),
    (0x1100, 0x1200, "hangul jamo"),
    (0x3000, 0x3040, "CJK symbols and punctuation"),
    (0x3130, 0x3190, "hangul compatibility jamo"),
    (0x3040, 0x3100, "kana"),
    (0x31F0, 0x3200, "katakana extended"),
    (0x3200, 0x3400, "enclosed CJK and compatibility"),
    (0x4E00, 0x9FFF, "CJK unified"),
    (0xAC00, 0xD7B0, "hangul syllables"),
    (0xF900, 0xFB00, "compatibility ideographs"),
    (0xFE30, 0xFE50, "CJK compatibility forms"),
    (0xFF00, 0xFFF0, "halfwidth and fullwidth forms"),
]


def first_existing(paths, what):
    for p in paths:
        if os.path.isfile(p):
            return p
    sys.exit(f"{what} not found; looked in:\n  " + "\n  ".join(paths))


def runs_of(codepoints):
    """Contiguous runs, as a flat list of inclusive start/end pairs."""
    out = []
    for cp in sorted(codepoints):
        if out and cp == out[-1][1] + 1:
            out[-1][1] = cp
        else:
            out.append([cp, cp])
    return out


def write_coverage_table():
    """Emits the codepoints that justify leaving the UI face, as Kotlin.

    Not the UI face's own table, which is the question one step removed. What a
    selector needs to know is whether switching would HELP: a character neither
    bundled face can draw is a box either way, so routing a whole string to a
    7.8 MB face because of one is pure loss. The first version asked "does the UI
    face cover everything", and answered no for a byte-order mark, a narrow
    no-break space and a word joiner, none of which either face carries and none
    of which draws anything at all.

    Read out of the two shipped binaries rather than out of the sources they were
    subset from, because what ships is what has to answer.
    """
    ui = set(TTFont(UI_FACE).getBestCmap())
    cjk = set(TTFont(FONT_OUT).getBestCmap())
    only = {cp: None for cp in sorted(cjk - ui)}
    runs = runs_of(only)
    lines = [
        "// Generated by tools/fonts/regenerate.py -- do not edit by hand.",
        "// Codepoints the bundled CJK face draws and the bundled UI face does not,",
        "// read out of the two shipped files themselves.",
        "package hivens.ui.text",
        "",
        "/**",
        " * Inclusive codepoint runs that justify leaving the UI face, flattened to",
        f" * start/end pairs. {len(only)} codepoints in {len(runs)} runs.",
        " *",
        " * A codepoint absent from BOTH faces is absent here: switching for it would",
        " * trade one missing glyph for the same missing glyph plus a 7.8 MB face.",
        " */",
        "internal val CJK_ONLY_RUNS: IntArray = intArrayOf(",
    ]
    for a, b in runs:
        lines.append(f"    0x{a:04X}, 0x{b:04X},")
    lines += [")", ""]
    os.makedirs(os.path.dirname(COVERAGE_OUT), exist_ok=True)
    with open(COVERAGE_OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    return len(only), len(runs)


def main():
    src = first_existing(SOURCE_CANDIDATES, "the pan-CJK Noto Sans collection")
    lic = first_existing(LICENSE_CANDIDATES, "the Noto CJK licence text")

    coll = TTCollection(src)
    face = None
    for i, f in enumerate(coll.fonts):
        name = f["name"].getDebugName(1)
        if name.endswith(FACE_SUFFIX) and "Mono" not in name:
            face = (i, name, f.getBestCmap())
            break
    if face is None:
        sys.exit(f"no face ending in {FACE_SUFFIX!r} inside {src}")
    index, name, cmap = face

    wanted = []
    for lo, hi, label in RANGES:
        present = [cp for cp in range(lo, hi) if cp in cmap]
        wanted += present
        print(f"  {label:32s} {len(present):6d} / {hi - lo}")
    wanted = sorted(set(wanted))

    os.makedirs(os.path.dirname(FONT_OUT), exist_ok=True)
    unicodes = os.path.join(os.path.dirname(FONT_OUT), ".cjk-unicodes.tmp")
    with open(unicodes, "w", encoding="utf-8") as f:
        f.write("\n".join(f"U+{cp:04X}" for cp in wanted))
    try:
        subprocess.run(
            [
                "pyftsubset", src,
                f"--font-number={index}",
                f"--unicodes-file={unicodes}",
                # Rendered by codepoint, so the OpenType layout tables are dead
                # weight here exactly as they are for the icon subset.
                "--layout-features=",
                "--output-file=" + FONT_OUT,
            ],
            check=True,
        )
    finally:
        os.remove(unicodes)

    with open(lic, encoding="utf-8", errors="replace") as fin:
        text = fin.read()
    os.makedirs(os.path.dirname(LICENSE_OUT), exist_ok=True)
    with open(LICENSE_OUT, "w", encoding="utf-8") as fout:
        fout.write(text)

    covered, run_count = write_coverage_table()

    print(f"\nsource:  {src}")
    print(f"face:    [{index}] {name}")
    print(f"kept:    {len(wanted)} codepoints of {len(cmap)} in the face")
    print(f"font:    {os.path.relpath(FONT_OUT, ROOT)}  ({os.path.getsize(FONT_OUT) // 1024} KB)")
    print(f"licence: {os.path.relpath(LICENSE_OUT, ROOT)}")
    print(f"ui face: {os.path.relpath(COVERAGE_OUT, ROOT)}  "
          f"({covered} codepoints in {run_count} runs, from {os.path.basename(UI_FACE)})")


if __name__ == "__main__":
    main()
