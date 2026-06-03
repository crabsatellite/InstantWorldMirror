from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts import check_readme_code_map as checker


class ReadmeCodeMapCheckerTest(unittest.TestCase):
    def test_readme_and_code_anchors_pass_together(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "README.md").write_text("A mirror creates a portal.\n", encoding="utf-8")
            source = root / "src" / "Example.java"
            source.parent.mkdir(parents=True)
            source.write_text(
                "class Example { public boolean createPortal() { checkBlock(); openPortal(); return true; } }\n",
                encoding="utf-8",
            )
            manifest = root / "docs" / "readme-code-map.json"
            manifest.parent.mkdir()
            manifest.write_text(json.dumps({
                "readme": "README.md",
                "features": [{
                    "id": "portal",
                    "readmeTexts": ["A mirror creates a portal."],
                    "code": [{
                        "adapter": "method_contains_in_order",
                        "file": "src/Example.java",
                        "method": "createPortal",
                        "texts": ["checkBlock()", "openPortal()"],
                    }],
                }],
            }), encoding="utf-8")

            self.assertEqual([], checker.check_project(root, manifest))

            (root / "README.md").write_text("A mirror does something else.\n", encoding="utf-8")
            failures = checker.check_project(root, manifest)
            self.assertEqual(1, len(failures))
            self.assertIn("README missing", failures[0])

    def test_code_anchor_failure_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "README.md").write_text("Cooldown is reduced by Efficiency.\n", encoding="utf-8")
            source = root / "src" / "Example.java"
            source.parent.mkdir(parents=True)
            source.write_text("class Example { public int cooldown() { return 300; } }\n", encoding="utf-8")
            manifest = root / "docs" / "readme-code-map.json"
            manifest.parent.mkdir()
            manifest.write_text(json.dumps({
                "readme": "README.md",
                "features": [{
                    "id": "cooldown",
                    "readmeTexts": ["Cooldown is reduced by Efficiency."],
                    "code": [{
                        "adapter": "method_contains_all",
                        "file": "src/Example.java",
                        "method": "cooldown",
                        "texts": ["efficiencyLevel"],
                    }],
                }],
            }), encoding="utf-8")

            failures = checker.check_project(root, manifest)
            self.assertEqual(1, len(failures))
            self.assertIn("efficiencyLevel", failures[0])


if __name__ == "__main__":
    unittest.main()
