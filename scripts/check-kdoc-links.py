#!/usr/bin/env python3
# Finds KDoc links that point at nothing: a `[Name]` or `[Name.member]` whose
# name is declared nowhere, which is what a rename, a deletion or a typo
# leaves behind.
#
# The IDE's inspection for unresolved KDoc links cannot be used for this.
# A lower module may link a symbol of an upper module it cannot import, and that
# link is fine. So this does not resolve a link the way the compiler would. It
# asks a weaker question that has no such false positives: does the name exist
# anywhere at all, in this repository or in a library the build compiles
# against.
#
# What counts as existing, and why each source is trusted:
#
#   * Any identifier in compiled code, in any module. Code compiles, so every
#     name it spells resolves to something real: a declaration here (class,
#     fun interface, function, property, parameter, type parameter, enum entry
#     in any layout) or a library symbol the code uses. Comments and string
#     bodies are blanked first, so only code counts.
#   * The names the linking file imports, alias included.
#   * The names Kotlin imports into every file (kotlin.*, kotlin.collections.*,
#     java.lang.* and the rest), listed in DEFAULT_IMPORTS. A KDoc may name
#     `LinkageError` or `runCatching` that no code in the tree spells.
#   * A fully qualified link into a library package (`[java.io.File]`): one
#     outside every package declared here, under a package some import or
#     qualified code reference uses, or under java, javax, kotlin or kotlinx.
#     Library members cannot be checked without the library sources. A
#     qualified link into a package of this repository is checked like any
#     other: `[hivens.core.data.Gone]` is a finding.
#
# A qualified link whose head is a class of this repository is checked one step
# further: the next segment has to be declared in that class, in a repository
# supertype of it, in an extension on it, or be one of the members every class,
# enum or data class has. That catches `[Bootstrap.preBoot]` when a `preBoot`
# survives somewhere else. A class with a library supertype is left open, since
# its inherited members are not visible here.
#
# Usage: python3 -I scripts/check-kdoc-links.py [--strict] [paths...]
# Paths limit which files are reported on. The name index always covers the
# whole tree, so a narrowed run reports nothing a full run would not.

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from bisect import bisect_left
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

KOTLIN_EXTS = {".kt", ".kts"}
SOURCE_EXTS = KOTLIN_EXTS | {".java"}

# Kotlin's default imports. Types and top-level functions only: an extension
# such as `Iterable.map` is linked through its receiver, whose head is here.
DEFAULT_IMPORTS = frozenset(
    """
    AbstractMethodError Appendable ArithmeticException ArrayIndexOutOfBoundsException
    ArrayStoreException AssertionError AutoCloseable Boolean BootstrapMethodError Byte
    Character CharSequence Class ClassCastException ClassCircularityError
    ClassFormatError ClassLoader ClassNotFoundException ClassValue
    CloneNotSupportedException Cloneable Comparable Deprecated Double Enum
    EnumConstantNotPresentException Error Exception ExceptionInInitializerError Float
    FunctionalInterface IllegalAccessError IllegalAccessException
    IllegalArgumentException IllegalCallerException IllegalMonitorStateException
    IllegalStateException IllegalThreadStateException IncompatibleClassChangeError
    IndexOutOfBoundsException InheritableThreadLocal InstantiationError
    InstantiationException Integer InternalError InterruptedException Iterable
    LayerInstantiationException LinkageError Long MatchException Math Module
    ModuleLayer NegativeArraySizeException NoClassDefFoundError NoSuchFieldError
    NoSuchFieldException NoSuchMethodError NoSuchMethodException NullPointerException
    Number NumberFormatException Object OutOfMemoryError Override Package Process
    ProcessBuilder ProcessHandle Readable Record ReflectiveOperationException Runnable
    Runtime RuntimeException RuntimePermission SafeVarargs ScopedValue
    SecurityException SecurityManager Short StackOverflowError StackTraceElement
    StackWalker StrictMath String StringBuffer StringBuilder
    StringIndexOutOfBoundsException SuppressWarnings System Thread ThreadGroup
    ThreadLocal Throwable TypeNotPresentException UnknownError UnsatisfiedLinkError
    UnsupportedClassVersionError UnsupportedOperationException VerifyError
    VirtualMachineError Void WrongThreadException

    Any Nothing Unit Int Char UByte UShort UInt ULong Array BooleanArray ByteArray
    ShortArray IntArray LongArray FloatArray DoubleArray CharArray UByteArray
    UShortArray UIntArray ULongArray NoSuchElementException
    ConcurrentModificationException NoWhenBranchMatchedException
    UninitializedPropertyAccessException NotImplementedError KotlinNullPointerException
    Pair Triple Lazy LazyThreadSafetyMode Result Comparator Annotation Function
    DeprecationLevel DeprecatedSinceKotlin ReplaceWith Suppress PublishedApi DslMarker
    OptIn RequiresOptIn SinceKotlin ExperimentalStdlibApi ExperimentalUnsignedTypes
    Metadata Target Retention Repeatable MustBeDocumented AnnotationTarget
    AnnotationRetention JvmStatic JvmField JvmOverloads JvmName JvmInline JvmRecord
    JvmWildcard JvmSuppressWildcards JvmMultifileClass JvmSynthetic JvmSerializableLambda
    Throws Synchronized Volatile Transient Strictfp
    Iterator MutableIterator ListIterator MutableListIterator MutableIterable
    Collection MutableCollection List MutableList Set MutableSet Map MutableMap
    ArrayList HashMap HashSet LinkedHashMap LinkedHashSet ArrayDeque AbstractList
    AbstractMutableList AbstractCollection AbstractSet AbstractMap Grouping
    IndexedValue Sequence ClosedRange OpenEndRange ClosedFloatingPointRange IntRange
    LongRange CharRange UIntRange ULongRange IntProgression LongProgression
    CharProgression Regex RegexOption MatchResult MatchGroup MatchGroupCollection
    Charsets Typography CharCategory CharDirectionality FileWalkDirection
    FileTreeWalk OnErrorAction

    println print readln readlnOrNull readLine error require requireNotNull check
    checkNotNull assert TODO run let also apply with takeIf takeUnless repeat lazy
    lazyOf runCatching synchronized use to emptyArray arrayOf arrayOfNulls
    booleanArrayOf byteArrayOf shortArrayOf intArrayOf longArrayOf floatArrayOf
    doubleArrayOf charArrayOf listOf listOfNotNull mutableListOf arrayListOf emptyList
    buildList setOf setOfNotNull mutableSetOf hashSetOf linkedSetOf emptySet buildSet
    mapOf mutableMapOf hashMapOf linkedMapOf emptyMap buildMap sequenceOf
    emptySequence sequence iterator generateSequence buildString maxOf minOf compareBy
    compareByDescending compareValues compareValuesBy naturalOrder reverseOrder
    nullsFirst nullsLast enumValues enumValueOf enumEntries suspend
    """.split()
)

# Package roots of the JDK and the Kotlin libraries. A KDoc may qualify a name
# into one of them (`[java.util.concurrent.ConcurrentHashMap]`) that no import
# or code in the tree reaches.
LIBRARY_ROOTS = frozenset({"java", "javax", "kotlin", "kotlinx"})

# Members every class has (Companion included, so `Foo.Companion.bar` passes
# through to the companion's members), and the ones an enum or a data class adds.
ANY_MEMBERS = frozenset({"equals", "hashCode", "toString", "javaClass", "Companion"})
ENUM_MEMBERS = frozenset({"name", "ordinal", "entries", "values", "valueOf", "compareTo"})
DATA_MEMBERS = frozenset({"copy"})

# A word that opens a member, ending an enum's entry list when no `;` does.
MEMBER_WORDS = frozenset(
    """fun val var companion init constructor private protected internal public
    override abstract open sealed data inner enum annotation class object
    interface typealias const lateinit inline operator infix suspend tailrec
    external final""".split()
)

IDENT = r"(?:`[^`\n]+`|[^\W\d]\w*)"
IDENT_RE = re.compile(IDENT)
LINK_TARGET = rf"{IDENT}(?:\.{IDENT})*(?:\.\.{IDENT}(?:\.{IDENT})*)?"
LINK_RE = re.compile(rf"\[({LINK_TARGET})\]")
TAG_RE = re.compile(rf"@(?:see|throws|exception)\s+({IDENT}(?:\.{IDENT})*)")
PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)
IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+?)(\.\*)?(?:\s+as\s+(\w+))?\s*;?\s*$", re.MULTILINE)
QUALIFIED_RE = re.compile(r"\b[a-z]\w*(?:\.\w+)+")


def unquote(name: str) -> str:
    return name[1:-1] if name.startswith("`") else name


# Lexing. Kotlin nests block comments and has string templates, so a regex over
# the raw text would read code inside a template as a string and a `*/` inside
# a string as a comment end. This walks the text once and blanks comments and
# string bodies with spaces, newlines kept, so offsets and lines still match.


_NESTED_MARK = re.compile(r"/\*|\*/")
_CLOSE_MARK = re.compile(r"\*/")


def _skip_block_comment(text: str, i: int, nests: bool) -> int:
    depth, j = 1, i + 2
    marks = _NESTED_MARK if nests else _CLOSE_MARK
    while True:
        m = marks.search(text, j)
        if m is None:
            return len(text)
        j = m.end()
        if m.group(0) == "/*":
            depth += 1
        else:
            depth -= 1
            if depth == 0:
                return j


def _skip_char(text: str, i: int) -> int:
    j = i + 1
    if j < len(text) and text[j] == "\\":
        j += 2
    k = text.find("'", j)
    if k < 0 or k - i > 12 or "\n" in text[i:k]:
        return i + 1
    return k + 1


def _skip_template(text: str, j: int, kotlin: bool) -> int:
    depth, n = 1, len(text)
    while j < n:
        ch = text[j]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return j + 1
        elif ch == '"':
            j = _skip_string(text, j, kotlin)
            continue
        elif ch == "'":
            j = _skip_char(text, j)
            continue
        elif text.startswith("//", j):
            k = text.find("\n", j)
            j = n if k < 0 else k
            continue
        elif text.startswith("/*", j):
            j = _skip_block_comment(text, j, kotlin)
            continue
        j += 1
    return n


def _skip_string(text: str, i: int, kotlin: bool) -> int:
    n = len(text)
    # A multi-dollar literal ($$"...") opens a template only with as many dollars,
    # so a single `${` in it is plain text.
    dollars = 1
    while kotlin and text[i - dollars : i - dollars + 1] == "$":
        dollars += 1
    opener = "$" * dollars + "{"
    if text.startswith('"""', i):
        j = i + 3
        while j < n:
            if kotlin and text.startswith(opener, j):
                j = _skip_template(text, j + len(opener), kotlin)
            elif text.startswith('"""', j):
                j += 3
                while j < n and text[j] == '"':
                    j += 1
                return j
            else:
                j += 1
        return n
    j = i + 1
    while j < n:
        ch = text[j]
        if ch == "\\":
            j += 2
        elif kotlin and text.startswith(opener, j):
            j = _skip_template(text, j + len(opener), kotlin)
        elif ch == '"':
            return j + 1
        elif ch == "\n":
            return j
        else:
            j += 1
    return n


_NOT_NEWLINE = re.compile(r"[^\n]")


def _blank(s: str) -> str:
    return _NOT_NEWLINE.sub(" ", s)


_INTERESTING = re.compile(r"[/\"'`]")


def lex(text: str, kotlin: bool) -> tuple[str, list[tuple[int, int]]]:
    """Code with comments and literals blanked, and the spans of the KDocs."""
    out: list[str] = []
    kdocs: list[tuple[int, int]] = []
    i, n = 0, len(text)
    while i < n:
        m = _INTERESTING.search(text, i)
        if m is None:
            out.append(text[i:])
            break
        if m.start() > i:
            out.append(text[i : m.start()])
            i = m.start()
        ch = text[i]
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
        elif text.startswith("/*", i):
            j = _skip_block_comment(text, i, kotlin)
            if kotlin and text.startswith("/**", i) and not text.startswith("/**/", i):
                kdocs.append((i, j))
        elif ch == '"':
            j = _skip_string(text, i, kotlin)
        elif ch == "'":
            j = _skip_char(text, i)
            if j == i + 1:
                out.append(ch)
                i = j
                continue
        elif ch == "`" and kotlin:
            # A backticked name, often a test name with an apostrophe in it.
            j = text.find("`", i + 1)
            if j < 0 or "\n" in text[i:j]:
                out.append(ch)
                i += 1
                continue
            out.append(text[i : j + 1])
            i = j + 1
            continue
        else:
            out.append(ch)
            i += 1
            continue
        out.append(_blank(text[i:j]))
        i = j
    return "".join(out), kdocs


# Declarations, for the member check. Run over lexed code only.


def _match_angle(code: str, i: int) -> int:
    """Index after the `>` closing the `<` at i, or i when it is not a type list."""
    depth, j, n = 0, i, len(code)
    while j < n:
        ch = code[j]
        if ch == "<":
            depth += 1
        elif ch == ">" and code[j - 1] != "-":
            depth -= 1
            if depth == 0:
                return j + 1
        elif ch in "{};=" or (ch == "\n" and depth == 0):
            return i
        j += 1
    return i


def _match_bracket(code: str, i: int) -> int:
    """Index after the bracket closing the one at i."""
    pairs = {"(": ")", "{": "}", "[": "]"}
    stack = [pairs[code[i]]]
    j, n = i + 1, len(code)
    while j < n and stack:
        ch = code[j]
        if ch in pairs:
            stack.append(pairs[ch])
        elif ch in ")}]":
            if ch == stack[-1]:
                stack.pop()
        j += 1
    return j


def _type_params(text: str) -> dict[str, str]:
    """`<in T : Bound, reified R>` -> {T: Bound, R: ""}."""
    found: dict[str, str] = {}
    depth, start, parts = 0, 1, []
    for k, ch in enumerate(text):
        if ch == "<":
            depth += 1
        elif ch == ">" and text[k - 1] != "-":
            depth -= 1
        elif ch == "," and depth == 1:
            parts.append(text[start:k])
            start = k + 1
    parts.append(text[start:-1])
    for part in parts:
        m = re.match(r"\s*(?:@\w+\s+)*(?:(?:in|out|reified)\s+)*(\w+)\s*(?::\s*([\w.]+))?", part)
        if m:
            found[m.group(1)] = (m.group(2) or "").split(".")[-1]
    return found


def _base_names(receiver: str) -> list[str]:
    """The type names a receiver such as `Foo.Bar<T>?` names."""
    flat, depth = [], 0
    for ch in receiver:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
        elif depth == 0:
            flat.append(ch)
    return [unquote(s) for s in IDENT_RE.findall("".join(flat))]


@dataclass
class ClassRecord:
    name: str
    kind: str
    modifiers: set[str]
    members: set[str] = field(default_factory=set)
    supers: list[str] = field(default_factory=list)
    nested: set[str] = field(default_factory=set)
    open: bool = False


@dataclass
class Index:
    code_names: set[str] = field(default_factory=set)
    declared: set[str] = field(default_factory=set)
    classes: dict[str, list[ClassRecord]] = field(default_factory=dict)
    extensions: dict[str, set[str]] = field(default_factory=dict)
    repo_packages: set[str] = field(default_factory=set)
    package_prefixes: set[str] = field(default_factory=set)
    package_names: dict[str, set[str]] = field(default_factory=dict)
    external_names: set[str] = field(default_factory=set)
    external_packages: set[str] = field(default_factory=set)


def _callable_name(code: str, j: int, stops: str) -> tuple[str, str, dict[str, str], int]:
    """After `fun` or `val`: (receiver, name, type params, end)."""
    n = len(code)
    while j < n and code[j] in " \t":
        j += 1
    tparams: dict[str, str] = {}
    if j < n and code[j] == "<":
        end = _match_angle(code, j)
        if end > j:
            tparams = _type_params(code[j:end])
            j = end
            while j < n and code[j] in " \t":
                j += 1
    start, depth = j, 0
    while j < n:
        ch = code[j]
        if ch == "<":
            depth += 1
        elif ch == ">" and code[j - 1] != "-":
            depth -= 1
        elif depth == 0 and (ch in stops or ch in " \t"):
            # A receiver has no space outside its type arguments, so the first
            # one ends the name: `val x get() = ...`, `val y by lazy`.
            break
        j += 1
    chain = code[start:j].strip()
    names = IDENT_RE.findall(chain)
    if not names:
        return "", "", tparams, j
    name = names[-1]
    cut = chain.rfind(name)
    receiver = chain[:cut].rstrip(". ?") if cut > 0 else ""
    return receiver, unquote(name), tparams, j


FUN_RE = re.compile(r"\bfun\b")
PROP_RE = re.compile(r"\b(?:val|var)\b")
CLASS_RE = re.compile(rf"\b(class|interface|object|typealias)\s+({IDENT})")
COMPANION_RE = re.compile(r"\bcompanion\s+object\b(?!\s+[^\W\d])")
PARAM_RE = re.compile(rf"({IDENT})\s*:(?!:)")
LAMBDA_RE = re.compile(r"\{\s*([\w\s,():<>?.]*?)->")
FOR_RE = re.compile(r"\bfor\s*\(\s*(\([^()]*\)|[^\W\d]\w*)")


def declarations(code: str, index: Index) -> list[tuple[int, str]]:
    """(offset, name) of every name declared in code, sorted. Records extensions."""
    found: list[tuple[int, str]] = []
    for m in FUN_RE.finditer(code):
        rest = code[m.end() : m.end() + 20].lstrip()
        if rest.startswith("interface"):
            continue
        receiver, name, tparams, _ = _callable_name(code, m.end(), "(={\n")
        found.extend((m.start(), t) for t in tparams)
        if name:
            found.append((m.start(), name))
            if receiver:
                _record_extension(index, receiver, name, tparams)
    for m in PROP_RE.finditer(code):
        j = m.end()
        while j < len(code) and code[j] in " \t":
            j += 1
        if j < len(code) and code[j] == "(":
            names = IDENT_RE.findall(code[j : _match_bracket(code, j)])
            found.extend((m.start(), unquote(s)) for s in names)
            continue
        receiver, name, tparams, _ = _callable_name(code, j, ":=\n),;{")
        found.extend((m.start(), t) for t in tparams)
        if name:
            found.append((m.start(), name))
            if receiver:
                _record_extension(index, receiver, name, tparams)
    for m in CLASS_RE.finditer(code):
        if code[max(0, m.start() - 2) : m.start()] == "::":
            continue
        found.append((m.start(), unquote(m.group(2))))
        j = m.end()
        if j < len(code) and code[j] == "<":
            found.extend((m.start(), t) for t in _type_params(code[j : _match_angle(code, j)]))
    for m in COMPANION_RE.finditer(code):
        found.append((m.start(), "Companion"))
    found.extend((m.start(), unquote(m.group(1))) for m in PARAM_RE.finditer(code))
    for pattern in (LAMBDA_RE, FOR_RE):
        for m in pattern.finditer(code):
            found.extend((m.start(), unquote(s)) for s in IDENT_RE.findall(m.group(1)))
    found.sort()
    return found


def _record_extension(index: Index, receiver: str, name: str, tparams: dict[str, str]) -> None:
    bases = _base_names(receiver)
    if len(bases) == 1 and bases[0] in tparams:
        bound = tparams[bases[0]]
        bases = [bound] if bound else ["*"]
    for base in bases:
        index.extensions.setdefault(base, set()).add(name)


def _modifiers(code: str, start: int) -> set[str]:
    k = start
    while k > 0 and code[k - 1] not in "\n;{}(":
        k -= 1
    return set(re.findall(r"\w+", code[k:start]))


def _enum_entries(body: str) -> set[str]:
    entries: set[str] = set()
    depth, expect, i, n = 0, True, 0, len(body)
    while i < n:
        ch = body[i]
        if depth == 0 and ch == ";":
            break
        if ch in "({[":
            depth += 1
        elif ch in ")}]":
            depth -= 1
        elif depth == 0 and ch == ",":
            expect = True
        elif depth == 0 and ch == "@":
            m = re.compile(r"@[\w.:]+").match(body, i)
            i = m.end() if m else i + 1
            continue
        elif depth == 0 and (ch.isalpha() or ch in "_`"):
            m = IDENT_RE.match(body, i)
            if m:
                word = unquote(m.group(0))
                if expect:
                    if word in MEMBER_WORDS:
                        break
                    entries.add(word)
                    expect = False
                i = m.end()
                continue
        i += 1
    return entries


def _header(code: str, j: int, kind: str) -> tuple[str, str, int | None]:
    """After a class name: (constructor params, supertypes, body start or None)."""
    n = len(code)
    if j < n and code[j] == "<":
        j = max(j, _match_angle(code, j))
    m = re.compile(r"[ \t]*(?:@\w+(?:\([^()]*\))?\s*|(?:private|protected|internal|public)\s+)*(?:constructor\s*)?").match(code, j)
    j = m.end() if m else j
    params = ""
    if j < n and code[j] == "(":
        end = _match_bracket(code, j)
        params = code[j:end]
        j = end
    if kind == "typealias":
        m = re.compile(r"\s*=\s*([^\n]*)").match(code, j)
        return "", m.group(1) if m else "", None
    supers, depth, k = [], 0, j
    seen_colon = False
    while k < n:
        ch = code[k]
        if depth == 0:
            if ch == "{":
                return params, "".join(supers), k
            if ch in ";}=" or (ch == ")" and depth == 0):
                break
            if ch == "\n":
                tail = "".join(supers).rstrip()
                nxt = code[k:].lstrip()
                if not (tail.endswith((",", ":")) or nxt.startswith((",", ":", "{", "by ", "where "))):
                    break
            if ch == ":" and not seen_colon and not code.startswith("::", k):
                seen_colon = True
                k += 1
                continue
        if ch == "(":
            end = _match_bracket(code, k)
            if seen_colon:
                supers.append(code[k:end])
            k = end
            continue
        if ch == "<":
            depth += 1
        elif ch == ">" and code[k - 1] != "-":
            depth -= 1
        if seen_colon:
            supers.append(ch)
        k += 1
    return params, "".join(supers), None


def _supertype_names(text: str) -> list[str]:
    parts, depth, cur = [], 0, []
    for ch in text:
        if ch in "<(":
            depth += 1
        elif ch in ">)":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur))
    names = []
    for part in parts:
        part = re.sub(r"@\w+(?:\([^()]*\))?", "", part)
        part = re.split(r"\bby\b|\bwhere\b", part)[0].strip()
        if not part:
            continue
        m = re.match(r"([\w.]+)", part)
        names.append(m.group(1).split(".")[-1] if m else "")
    return names


def index_classes(code: str, decls: list[tuple[int, str]], index: Index) -> None:
    offsets = [pos for pos, _ in decls]
    for m in CLASS_RE.finditer(code):
        if code[max(0, m.start() - 2) : m.start()] == "::":
            continue
        kind, name = m.group(1), unquote(m.group(2))
        modifiers = _modifiers(code, m.start())
        rec = ClassRecord(name=name, kind=kind, modifiers=modifiers)
        params, supers, body = _header(code, m.end(), kind)
        if kind == "typealias":
            target = _supertype_names(supers)
            if target and target[0]:
                rec.supers = [target[0]]
            else:
                rec.open = True
        else:
            rec.members |= {unquote(p) for p in PARAM_RE.findall(params)}
            rec.supers = _supertype_names(supers)
            if "" in rec.supers:
                rec.open = True
            if body is not None:
                end = _match_bracket(code, body)
                inner = code[body + 1 : end - 1]
                lo, hi = bisect_left(offsets, body), bisect_left(offsets, end)
                rec.members |= {name for _, name in decls[lo:hi]}
                rec.nested = {unquote(c.group(2)) for c in CLASS_RE.finditer(inner)}
                if "enum" in modifiers:
                    entries = _enum_entries(inner)
                    rec.members |= entries
                    index.declared |= entries
        index.classes.setdefault(name, []).append(rec)
    for m in COMPANION_RE.finditer(code):
        index.classes.setdefault("Companion", []).append(ClassRecord("Companion", "object", set(), open=True))


# The tree.


def source_files() -> list[Path]:
    # Tracked and untracked files minus what .gitignore drops, which is how build
    # output and scratch probes under out/ stay out of the name index. A file
    # Gradle does not compile cannot vouch that a name exists.
    listed = subprocess.run(
        ["git", "-C", str(ROOT), "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        check=True,
        capture_output=True,
    ).stdout.decode("utf-8")
    files = []
    for rel in listed.split("\0"):
        path = ROOT / rel
        if rel and path.suffix in SOURCE_EXTS and path.is_file():
            files.append(path)
    return sorted(files)


@dataclass
class SourceFile:
    path: Path
    text: str
    code: str
    kdocs: list[tuple[int, int]]
    package: str
    imports: dict[str, str]
    star_imports: list[str]


def load(paths: list[Path]) -> tuple[list[SourceFile], Index]:
    index = Index()
    files: list[SourceFile] = []
    for path in paths:
        text = path.read_text(encoding="utf-8", errors="replace")
        code, kdocs = lex(text, path.suffix in KOTLIN_EXTS)
        pkg = PACKAGE_RE.search(code)
        package = pkg.group(1) if pkg else ""
        imports: dict[str, str] = {}
        stars: list[str] = []
        for im in IMPORT_RE.finditer(code):
            target, star, alias = im.groups()
            if star:
                stars.append(target)
            else:
                imports[alias or target.split(".")[-1]] = target
        files.append(SourceFile(path, text, code, kdocs, package, imports, stars))
        index.repo_packages.add(package)
        index.code_names |= {unquote(s) for s in IDENT_RE.findall(code)}
        decls = declarations(code, index)
        names = {name for _, name in decls}
        index.declared |= names
        index.package_names.setdefault(package, set()).update(names)
        index_classes(code, decls, index)
    for package in index.repo_packages:
        parts = package.split(".")
        index.package_prefixes.update(".".join(parts[:k]) for k in range(1, len(parts) + 1))
    # What the build resolves from a library or from generated sources: the
    # imports that name nothing declared here, and the qualified references code
    # makes into the same roots.
    for f in files:
        for target in f.imports.values():
            if not _in_repo_package(target, index):
                parts = target.split(".")
                index.external_names.add(parts[-1])
                index.external_packages.update(".".join(parts[:k]) for k in range(1, len(parts)))
        for target in f.star_imports:
            if target not in index.repo_packages and not _in_repo_package(target, index):
                parts = target.split(".")
                index.external_packages.update(".".join(parts[:k]) for k in range(1, len(parts) + 1))
    roots = {p.split(".")[0] for p in index.external_packages}
    for f in files:
        for q in QUALIFIED_RE.findall(f.code):
            parts = q.split(".")
            if parts[0] not in roots or _in_repo_package(q, index):
                continue
            k = next((i for i, p in enumerate(parts) if p[:1].isupper()), len(parts))
            index.external_packages.update(".".join(parts[:i]) for i in range(1, k + 1))
            index.external_names.update(parts[k:])
    return files, index


def _repo_depth(parts: list[str], index: Index) -> int:
    """How many leading segments name a package declared here, 0 when none do."""
    return next((k for k in range(len(parts), 0, -1) if ".".join(parts[:k]) in index.repo_packages), 0)


def _in_repo_package(qualified: str, index: Index) -> bool:
    """Whether a qualified name is a top level declaration of a package here."""
    parts = qualified.split(".")
    k = _repo_depth(parts, index)
    return 0 < k < len(parts) and parts[k] in index.package_names.get(".".join(parts[:k]), set())


# Checking.


def _members(name: str, index: Index, seen: set[str]) -> tuple[set[str], bool]:
    """Members of every repository class called name, and whether any is open."""
    if name in seen:
        return set(), False
    seen.add(name)
    found = set(ANY_MEMBERS) | index.extensions.get(name, set()) | index.extensions.get("*", set())
    is_open = False
    for rec in index.classes.get(name, []):
        found |= rec.members | rec.nested
        if rec.open:
            is_open = True
        if "enum" in rec.modifiers:
            found |= ENUM_MEMBERS
        if "data" in rec.modifiers:
            found |= DATA_MEMBERS | {f"component{k}" for k in range(1, 33)}
        for sup in rec.supers:
            if sup in index.classes:
                sub, sub_open = _members(sup, index, seen)
                found |= sub
                is_open = is_open or sub_open
            else:
                is_open = True
    return found, is_open


def _known(name: str, f: SourceFile, index: Index) -> bool:
    return name in index.code_names or name in f.imports or name in DEFAULT_IMPORTS


def _is_external(name: str, f: SourceFile, index: Index) -> bool:
    target = f.imports.get(name)
    if target is not None:
        return not _in_repo_package(target, index)
    if name in index.external_names or name in DEFAULT_IMPORTS:
        return True
    # Spelled in code but declared nowhere here: a library name.
    return name in index.code_names and name not in index.declared


def _check_scoped(cls: str, rest: list[str], f: SourceFile, index: Index, ambiguous: bool) -> str | None:
    for k, seg in enumerate(rest):
        members, is_open = _members(cls, index, set())
        if is_open:
            return None
        if seg not in members:
            if ambiguous:
                return None
            return f"{seg} is not a member of {cls}"
        nested = any(seg in rec.nested for rec in index.classes.get(cls, []))
        if seg == "Companion":
            continue
        if not nested:
            return _check_plain(rest[k + 1 :], f, index)
        cls = seg
    return None


def _check_plain(segs: list[str], f: SourceFile, index: Index) -> str | None:
    for seg in segs:
        if not _known(seg, f, index):
            return f"{seg} is declared nowhere"
    return None


def check_name(target: str, f: SourceFile, index: Index) -> str | None:
    if ".." in target:
        for side in target.split(".."):
            problem = check_name(side, f, index)
            if problem:
                return problem
        return None
    segs = [unquote(s) for s in IDENT_RE.findall(target)]
    head = segs[0]
    if len(segs) > 1 and head[0].islower():
        problem = _check_package(segs, f, index)
        if problem != "not a package":
            return problem
    if not _known(head, f, index):
        return f"{head} is declared nowhere"
    if len(segs) == 1:
        return None
    imported = f.imports.get(head)
    repo_import = imported is not None and _in_repo_package(imported, index)
    external = _is_external(head, f, index)
    if head in index.classes and (repo_import or imported is None):
        return _check_scoped(head, segs[1:], f, index, ambiguous=external and not repo_import)
    if external:
        return None
    return _check_plain(segs[1:], f, index)


def _check_package(segs: list[str], f: SourceFile, index: Index) -> str | None:
    """A fully qualified link, or "not a package" when the head is a plain name."""
    if ".".join(segs) in index.package_prefixes:
        return None
    if _is_external_package(segs, index):
        return None
    k = _repo_depth(segs, index)
    if k == 0:
        # Under a root of this repository, `hivens.nope.Thing`, unless the head
        # is a plain name that happens to share the root's spelling.
        depth = next((a for a in range(len(segs), 0, -1) if ".".join(segs[:a]) in index.package_prefixes), 0)
        if depth == 0 or segs[0] in index.declared:
            return "not a package"
        return f"{segs[depth]} is not a package under {'.'.join(segs[:depth])}"
    package, top = ".".join(segs[:k]), segs[k]
    if top not in index.package_names.get(package, set()):
        return f"{top} is not declared in package {package}"
    if top in index.classes and k + 1 < len(segs):
        return _check_scoped(top, segs[k + 1 :], f, index, ambiguous=False)
    return _check_plain(segs[k + 1 :], f, index)


def _is_external_package(segs: list[str], index: Index) -> bool:
    # Into a package this repository does not have, under a root a library uses.
    # A root both share, `dev.hivens` here and in a library, decides nothing.
    for k in range(len(segs) - 1, 0, -1):
        prefix = ".".join(segs[:k])
        if prefix in index.package_prefixes:
            return False
        if prefix in index.external_packages or prefix in LIBRARY_ROOTS:
            return True
    return False


def _doc_text(text: str, start: int, end: int) -> str:
    """The KDoc with its frame, line stars and code blocks blanked, offsets kept."""
    raw = text[start:end]
    lines = raw.split("\n")
    out, fenced = [], False
    for k, line in enumerate(lines):
        body = line
        if k == 0:
            body = "   " + line[3:]
        else:
            m = re.match(r"^(\s*\*(?!/))", line)
            if m:
                body = " " * len(m.group(1)) + line[m.end() :]
        if body.rstrip().endswith("*/"):
            cut = body.rstrip()
            body = cut[:-2] + "  " + body[len(cut) :]
        if body.strip().startswith(("```", "~~~")):
            fenced = not fenced
            out.append(_blank(body))
            continue
        out.append(_blank(body) if fenced else body)
    return "\n".join(out)


def _code_spans(doc: str, protected: list[tuple[int, int]]) -> list[tuple[int, int]]:
    """Inline code spans per CommonMark, ignoring backticks that quote a link name."""
    runs = []
    for m in re.finditer(r"`+", doc):
        if not any(a <= m.start() < b for a, b in protected):
            runs.append((m.start(), m.end()))
    spans, k = [], 0
    while k < len(runs):
        a, b = runs[k]
        width = b - a
        close = next(
            (q for q in range(k + 1, len(runs)) if runs[q][1] - runs[q][0] == width and "\n\n" not in doc[b : runs[q][0]]),
            None,
        )
        if close is None:
            k += 1
            continue
        spans.append((a, runs[close][1]))
        k = close + 1
    return spans


def doc_links(doc: str) -> list[tuple[int, str]]:
    """(offset, target) for every link in a KDoc body."""
    matches = list(LINK_RE.finditer(doc))
    spans = _code_spans(doc, [(m.start(), m.end()) for m in matches])
    found = []
    for m in matches:
        if any(a <= m.start() < b for a, b in spans):
            continue
        after = doc[m.end() : m.end() + 1]
        if after == "(":
            continue
        if after == "[" and LINK_RE.match(doc, m.end()):
            continue
        line_start = doc.rfind("\n", 0, m.start()) + 1
        if doc[line_start : m.start()].strip() == "" and doc.startswith(":", m.end()):
            continue
        found.append((m.start() + 1, m.group(1)))
    for m in TAG_RE.finditer(doc):
        if not any(a <= m.start() < b for a, b in spans):
            found.append((m.start(1), m.group(1)))
    return found


@dataclass(frozen=True)
class Finding:
    rel: str
    line: int
    target: str
    problem: str


def findings(files: list[SourceFile], index: Index, only: list[Path]) -> list[Finding]:
    out = []
    for f in files:
        if f.path.suffix not in KOTLIN_EXTS:
            continue
        if only and not any(f.path == p or p in f.path.parents for p in only):
            continue
        rel = f.path.relative_to(ROOT).as_posix()
        for start, end in f.kdocs:
            doc = _doc_text(f.text, start, end)
            for offset, target in doc_links(doc):
                problem = check_name(target, f, index)
                if problem:
                    line = f.text.count("\n", 0, start + offset) + 1
                    out.append(Finding(rel, line, target, problem))
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="KDoc links whose name is declared nowhere.")
    parser.add_argument("--strict", action="store_true", help="exit 1 on any finding")
    parser.add_argument("paths", nargs="*", type=Path, help="report only on these files or directories")
    args = parser.parse_args()

    only = [(p if p.is_absolute() else Path.cwd() / p).resolve() for p in args.paths]
    files, index = load(source_files())
    found = findings(files, index, only)
    for item in found:
        print(f"{item.rel}:{item.line}: [{item.target}] {item.problem}")
    kdocs = sum(len(f.kdocs) for f in files)
    if not found:
        print(f"kdoc-links: every link resolves to a name, {kdocs} KDocs in {len(files)} files")
        return 0
    print(f"\nkdoc-links: {len(found)} link(s) point at nothing")
    return 1 if args.strict else 0


if __name__ == "__main__":
    sys.exit(main())
