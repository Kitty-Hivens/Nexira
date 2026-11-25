#!/usr/bin/env python3
"""What check-android-api.py finds in a class, and what it leaves alone."""

import importlib.util
import struct
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("check_android_api", SCRIPTS / "check-android-api.py")
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)

TABLE = """<api version="4">
  <class name="java/lang/Object" since="1"><method name="hashCode()I"/></class>
  <class name="java/util/stream/BaseStream" since="24"><method name="close()V"/></class>
  <class name="java/util/stream/Stream" since="24">
    <extends name="java/lang/Object"/><implements name="java/util/stream/BaseStream"/>
    <method name="count()J"/><method name="toList()Ljava/util/List;" since="34"/>
  </class>
  <class name="java/util/HexFormat" since="34"><method name="of()Ljava/util/HexFormat;"/></class>
  <class name="java/nio/file/Files" since="26"><method name="writeString()V" since="36.1"/><field name="OLD" since="33"/></class>
  <class name="java/util/ArrayList" since="1"><extends name="java/util/AbstractList"/><method name="removeFirst()V" since="35"/></class>
  <class name="java/util/AbstractList" since="1"><method name="size()I"/></class>
</api>"""


class Pool:
    def __init__(self):
        self.out, self.n = b"", 1

    def add(self, data, width=1):
        self.out += data
        self.n += width
        return self.n - width

    def utf(self, text):
        raw = text.encode()
        return self.add(b"\x01" + struct.pack(">H", len(raw)) + raw)

    def cls(self, name):
        return self.add(b"\x07" + struct.pack(">H", self.utf(name)))

    def ref(self, tag, owner, name, descriptor):
        nt = struct.pack(">HH", self.utf(name), self.utf(descriptor))
        at = self.add(b"\x0c" + nt)
        return self.add(bytes([tag]) + struct.pack(">HH", self.cls(owner), at))


def class_file(refs=(), classes=(), source="Some.kt", long_first=False):
    pool = Pool()
    if long_first:
        pool.add(b"\x05" + b"\0" * 8, width=2)
    this = pool.cls("hivens/Some")
    for ref in refs:
        pool.ref(*ref)
    for name in classes:
        pool.cls(name)
    attr, value = pool.utf("SourceFile"), pool.utf(source)
    code = pool.utf("Code")
    member = struct.pack(">HHHH", 0, 0, 0, 1) + struct.pack(">HI", code, 3) + b"abc"
    body = struct.pack(">HHH", 0, this, 0) + struct.pack(">HH", 1, this) + struct.pack(">H", 0) + struct.pack(">H", 1) + member
    tail = struct.pack(">H", 1) + struct.pack(">HIH", attr, 2, value)
    return b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 71, pool.n) + pool.out + body + tail


class CheckAndroidApiTest(unittest.TestCase):
    def setUp(self):
        self.levels = check.Levels(TABLE)

    def faults(self, *refs, **more):
        return check.faults(class_file(refs, **more), self.levels)

    def test_a_method_that_came_later_is_found(self):
        self.assertEqual(
            ["java.util.stream.Stream.toList (API 34)"],
            self.faults((11, "java/util/stream/Stream", "toList", "()Ljava/util/List;")),
        )
        self.assertEqual(["java.util.ArrayList.removeFirst (API 35)"], self.faults((10, "java/util/ArrayList", "removeFirst", "()V")))

    def test_a_level_of_a_minor_release_counts(self):
        self.assertEqual(["java.nio.file.Files.writeString (API 36)"], self.faults((10, "java/nio/file/Files", "writeString", "()V")))

    def test_a_field_is_found_by_its_name(self):
        self.assertEqual(["java.nio.file.Files.OLD (API 33)"], self.faults((9, "java/nio/file/Files", "OLD", "I")))

    def test_what_the_oldest_has_is_left_alone_inherited_too(self):
        self.assertEqual([], self.faults(
            (11, "java/util/stream/Stream", "count", "()J"),
            (11, "java/util/stream/Stream", "close", "()V"),
            (11, "java/util/stream/Stream", "hashCode", "()I"),
            (10, "java/util/ArrayList", "size", "()I"),
        ))

    def test_a_class_android_never_had_is_named_once(self):
        self.assertEqual(["java.awt.Cursor (never on Android)"], self.faults((10, "java/awt/Cursor", "getType", "()I")))
        self.assertEqual(["java.util.HexFormat (API 34)"], self.faults((10, "java/util/HexFormat", "of", "()Ljava/util/HexFormat;")))

    def test_a_method_on_no_level_is_named(self):
        self.assertEqual(
            ["java.util.stream.Stream.mapMulti (never on Android)"],
            self.faults((11, "java/util/stream/Stream", "mapMulti", "()V")),
        )

    def test_an_array_of_a_class_is_that_class(self):
        self.assertEqual(["java.awt.Cursor (never on Android)"], self.faults(classes=["[Ljava/awt/Cursor;"]))
        self.assertEqual([], self.faults(classes=["[I", "[[J"]))
        self.assertEqual([], self.faults((10, "[Ljava/lang/Object;", "clone", "()Ljava/lang/Object;")))

    def test_what_d8_rewrites_and_what_is_no_platform_is_not_asked_about(self):
        self.assertEqual([], self.faults(
            (10, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants", "()V"),
            (10, "java/lang/runtime/ObjectMethods", "bootstrap", "()V"),
            (10, "kotlin/collections/ArrayDeque", "removeFirst", "()V"),
        ))

    def test_the_source_and_the_pool_are_read_past_a_wide_constant(self):
        this, source, members, _ = check.read_class(class_file([(10, "java/util/ArrayList", "size", "()I")], source="Wide.kt", long_first=True))
        self.assertEqual(("hivens/Some", "Wide.kt"), (this, source))
        self.assertEqual([("java/util/ArrayList", "size()I")], members)

    def test_the_newest_platform_is_read_by_number(self):
        with tempfile.TemporaryDirectory() as dir:
            for name in ("android-9", "android-36", "android-37.0", "android-37.2"):
                at = Path(dir, "platforms", name, "data")
                at.mkdir(parents=True)
                (at / "api-versions.xml").write_text(TABLE)
            self.assertEqual("android-37.2", check.table_of(Path(dir)).parent.parent.name)

    def test_a_source_of_aliases_compiles_to_nothing(self):
        with tempfile.TemporaryDirectory() as dir:
            source = Path(dir, "A.kt")
            for text, expected in (
                ("package a\n\ntypealias B = C\n", False),
                ("package a\n\nimport b.fun1\n", False),
                ("package a\n\ninternal fun b() = 1\n", True),
                ("package a\n\n@Composable\nprivate fun b() = 1\n", True),
                ("package a\n\ndata class B(val c: Int)\n", True),
            ):
                source.write_text(text)
                self.assertEqual(expected, check.declares(source), text)


if __name__ == "__main__":
    unittest.main()
