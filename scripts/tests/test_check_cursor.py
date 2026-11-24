#!/usr/bin/env python3
"""What check-cursor.py refuses, and what it leaves alone."""

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("check_cursor", SCRIPTS / "check-cursor.py")
check_cursor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_cursor)

HERE = "client-ui/src/jvmSharedMain/kotlin/hivens/ui/Somewhere.kt"


def lines(source, rel=HERE):
    return [n for n, _ in check_cursor.faults(rel, source)]


class CheckCursorTest(unittest.TestCase):
    def test_compose_s_own_presses_are_refused_by_import(self):
        for name in ("clickable", "combinedClickable", "selection.selectable", "selection.toggleable", "selection.triStateToggleable"):
            self.assertEqual([1], lines(f"import androidx.compose.foundation.{name}\n"), name)
            self.assertEqual([1], lines(f"import androidx.compose.foundation.{name} as press\n"), name)

    def test_the_look_s_presses_are_not(self):
        self.assertEqual([], lines("import hivens.ui.press.clickable\nval m = Modifier.clickable { }\n"))

    def test_a_wildcard_hides_no_press(self):
        self.assertEqual([2], lines("import androidx.compose.foundation.*\nval m = Modifier.clickable { }\n"))
        self.assertEqual([], lines("import androidx.compose.foundation.*\nimport hivens.ui.press.clickable\nval m = Modifier.clickable { }\n"))
        self.assertEqual([2], lines("import androidx.compose.material3.*\nval b = TextButton(onClick = {}) { }\n"))
        self.assertEqual([], lines("import androidx.compose.material3.*\nimport hivens.ui.press.TextButton\nval b = TextButton(onClick = {}) { }\n"))

    def test_material_s_buttons_are_refused_by_import(self):
        for name in check_cursor.WRAPPED:
            self.assertEqual([1], lines(f"import androidx.compose.material3.{name}\n"), name)
        self.assertEqual([], lines("import androidx.compose.material3.ButtonDefaults\nimport androidx.compose.material3.IconButtonDefaults\n"))

    def test_a_material_control_asks_for_its_pointer(self):
        control = "import androidx.compose.material3.Checkbox\nval c = Checkbox(\n    checked = on,\n    onCheckedChange = set,\n%s)\n"
        self.assertEqual([2], lines(control % ""))
        self.assertEqual([], lines(control % "    modifier = Modifier.pressCursor(),\n"))
        self.assertEqual([], lines("import androidx.compose.material3.Checkbox\nval c = Checkbox(on, onCheckedChange = null)\n"))

    def test_a_control_of_one_s_own_by_the_same_name_is_its_own(self):
        self.assertEqual([], lines("import androidx.compose.material3.*\nfun FilterChip(a: Int) = Unit\nval c = FilterChip(1)\n"))

    def test_a_pointer_set_past_the_axis_is_refused(self):
        self.assertEqual([1], lines("val m = Modifier.pointerHoverIcon(icon)\n"))
        self.assertEqual([1], lines("import java.awt.Cursor\n"))
        self.assertEqual([], lines("val m = Modifier.pointerHoverIcon(icon)\n", "look/cursor-api/src/x.kt"))
        self.assertEqual([], lines("import java.awt.Cursor\n", sorted(check_cursor.EXEMPT)[0]))

    def test_a_comment_is_no_press(self):
        self.assertEqual([], lines("// import androidx.compose.foundation.clickable\n/** pointerHoverIcon(icon) */\n * pointerHoverIcon(icon)\n"))

    def test_the_wrappers_themselves_may_name_what_they_wrap(self):
        self.assertEqual([], lines("import androidx.compose.foundation.clickable as foundationClickable\n", check_cursor.PRESS + "Press.kt"))

    def test_every_exemption_is_there(self):
        for rel in check_cursor.EXEMPT:
            self.assertTrue((check_cursor.ROOT / rel).is_file(), rel)


if __name__ == "__main__":
    unittest.main()
